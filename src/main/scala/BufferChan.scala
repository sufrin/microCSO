package org.sufrin.microCSO

import Alternation.EventOutcome
import Time.Nanoseconds

import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}
import java.util.concurrent.locks.LockSupport
import scala.collection.mutable

/**
 * A NON-SHARED buffered channel with the given `name`. If more than one reader arrives
 * before a writer (or vice-versa) the channel detects that it is being shared; but this
 * heuristic for detecting sharing is not complete, because a consumer that repeatedly
 * consumes at a faster rate than two producers will not be detectable this way.
 *
 * Use `SharedBufferChan` if sharing is required.
 *
 * @param name
 * @tparam T
 */
class BufferChan[T](val name: String, capacity: Int) extends Chan[T] {
  override def className: String = s"BufferChan.$name($capacity)"

  private[this] val reader, writer                      = new AtomicReference[Thread]
  private[this] val inputClosed, outputClosed, closed   = new AtomicBoolean(false)
  private[this] val buffer                              = new mutable.Queue[T](capacity)

  @inline private def isFull: Boolean  = buffer.length==capacity
  @inline private def isEmpty: Boolean = buffer.isEmpty

  locally { org.sufrin.microCSO.CSORuntime.addChannel(this) }


  /**
   *  Capture (an approximation to) the current state for debugger components
   */
  def currentState: String = {
    val wr = reader.get
    val ww = writer.get
    val result =
      if (ww == null && wr == null) "-"
      else {
        if (ww != null)
           s"![from ${ww.getName}]"
        else
           s"?[from ${wr.getName}]"
      }
    s"$result (${buffer.length}/${capacity}) ic=$inputClosed/oc=$outputClosed"
  }

  override def toString: String =
    s"""BufferChan.${this.name} $currentState"""

  /**
   *  Behaves as !(t()) when there is room in the buffer
   */
  def offer(t: ()=>T): EventOutcome[Nothing] = {
    if (outputClosed.get)
      EventOutcome.CLOSED
    else
    if (isFull)
      EventOutcome.FAILED
    else {
      val value = t()
      this.!(value)
      EventOutcome.SUCCEEDED
    }
  }

  def !(value: T) = {
    checkOutputOpen
    val current = Thread.currentThread
    val lastWriter = writer.getAndSet(current)
    assert(
      lastWriter == null,
      s"[${current.getName}]$name!($value) overtaking [${lastWriter.getName}]$name!($buffer)"
    )
    while (isFull && !outputClosed.get && !inputClosed.get)
    {
      LockSupport.park(this)
    }
    // the buffer is no longer full, or the inport or outport was closed
    checkOutputOpen                 // redundant check that outport not closed during the wait
    buffer.addOne(value)            // enqueue the value
    LockSupport.unpark(reader.get)  // tell a waiting reader
    writer.set(null)                // allow the next writer
  }

  /** Behaves as `result.set(?())` when  a writer is already committed */
  def poll(): EventOutcome[T] = {
    if (closed.get)
      EventOutcome.CLOSED
    else
      if (!isEmpty) {
        EventOutcome.POLLED(this.?(()))
      }
      else
        EventOutcome.FAILED
  }

  def ?(t: Unit): T = {
    checkInputOpen
    val current     = Thread.currentThread
    val lastReader  = reader.getAndSet(current)
    assert(
      lastReader == null,
      s"[${current.getName}]$name?() overtaking [${lastReader.getName}]$name?()"
    )
    // await an output event
    while (!outputClosed.get && isEmpty)
    {
      LockSupport.park(this)
    }
    // output is closed or something is in the buffer
    checkInputOpen                    // redundant check that inport not closed during the wait
    val result = buffer.removeHead()  // dequeue
    reader.set(null)                  // allow (the next) reader in
    LockSupport.unpark(writer.get)    // tell a waiting writer
    result
  }

  def canInput: Boolean  = !inputClosed.get
  def canOutput: Boolean = !inputClosed.get

  def closeIn(): Unit = {
    if (logging) finer(s"$this CLOSING INPUT")
    if (!inputClosed.getAndSet(true)) {
      if (logging) finer(s"$this CLOSED INPUT")
      LockSupport.unpark(writer.getAndSet(null))
    }
  }

  def closeOut(): Unit = {
    if (logging) finer(s"$this CLOSING OUTPUT")
    if (!outputClosed.getAndSet(true)) {
      if (logging) finer(s"$this CLOSED OUTPUT")
      LockSupport.unpark( reader.getAndSet(null) )
    }
  }

  @inline private[this] def checkInputOpen =
    if (inputClosed.get || (outputClosed.get && isEmpty)) {
      writer.set(null)
      reader.set(null)
      throw new termination.Closed(name)
    }

  @inline private[this] def checkOutputOpen =
    if (outputClosed.get) {
      writer.set(null)
      reader.set(null)
      throw new termination.Closed(name)
    }

  def readBefore(timeoutNS: Time.Nanoseconds): Option[T] = {
    val current = Thread.currentThread
    assert(
      reader.get == null,
      s"[${current.getName}]$name?() overtaking [${reader.get.getName}]$name?()"
    )
    checkInputOpen
    reader.set(current)
    val success =
      0 < Time.parkUntilElapsedOr(this, timeoutNS, closed.get || !isEmpty)
    checkInputOpen
    if (success) Some(this.?(())) else None
  }

  def writeBefore(timeoutNS: Time.Nanoseconds)(value: T): Boolean = {
    assert(
      writer.get == null,
      s"$name!($value) from ${Thread.currentThread.getName} overtaking $name!($buffer) [${writer.get.getName}]"
    )
    checkOutputOpen
    // LockSupport.unpark(reader.getAndSet(null)) // WHY?
    var success =
      0 < Time.parkUntilElapsedOr(this, timeoutNS, closed.get || !isFull)
    checkOutputOpen
    this.!(value)
    success
  }

}

class SharedBufferChan[T](name: String, capacity: Int, readers: Int=1, writers: Int = 1) extends BufferChan[T](name, capacity) {
  import java.util.concurrent.locks.{ReentrantLock => Lock}
  val rLock, wLock = new Lock()

  @inline final def withLock[T](lock: Lock) ( body: => T ): T = {
    lock.lock()
    try { body } catch { case exn: Throwable => throw exn } finally { lock.unlock() }
  }

  val readersLeft = new AtomicLong(readers)
  val writersLeft = new AtomicLong(writers)

  override def toString: String = s"SharedSyncChan.$name [readers=${readersLeft.get}/$readers, writers=${writersLeft.get}/$writers] $currentState"

  override def closeIn(): Unit =  {
    if (logging) fine(s"$this CLOSING INPUT")
    if (readersLeft.decrementAndGet()<=0) {
      super.closeIn()
    }
  }

  override def closeOut(): Unit = {
    if (logging) fine(s"$this CLOSING OUTPUT")
    if (writersLeft.decrementAndGet()<=0) {
      super.closeOut()
    }
  }

  override def ?(u: Unit): T = withLock(rLock) { super.?(()) }
  override def !(t: T): Unit = withLock(wLock) { super.!(t) }

  override def readBefore(timeoutNS: Nanoseconds): Option[T] = withLock(rLock) { super.readBefore(timeoutNS) }
  override def writeBefore(timeoutNS: Nanoseconds)(value: T) = withLock(wLock) { super.writeBefore(timeoutNS)(value) }
  override def poll(): EventOutcome[T] = withLock(rLock) { super.poll() }
  override def offer(t: ()=>T): EventOutcome[Nothing] = withLock(wLock) { super.offer(t) }

}

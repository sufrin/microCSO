package org.sufrin.microCSO

import org.sufrin.microCSO.Alternation.EventOutcome
import org.sufrin.microCSO.Time.Nanoseconds

import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}
import java.util.concurrent.locks.LockSupport

/**
 * A non-shared synchronized (unbuffered) channel with the given `name`. When `c` is
 * such a channel, the termination of `c!v` (in a writing proc) is synchronized with that
 * of `c?()` in a (distinct) reading proc. The event is called a rendezvous: thje first
 * proc to arrive at the rendezvous awaits the second. If more than one reader arrives
 * before a writer (or vice-versa) the channel detects that it is being shared; but this
 * heuristic for detecting sharing is not complete, because a consumer that repeatedly
 * consumes at a faster rate than two producers will not be detectable this way.
 *
 * @param name
 * @tparam T
 */
class SyncChan[T](val name: String) extends Chan[T] {
  override def className: String = s"SyncChan.$name"

  private[this] val reader, writer = new AtomicReference[Thread]
  private[this] val closed, full   = new AtomicBoolean(false)
  private[this] var buffer: T = _

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
          if (full.get)
            s"!($buffer) from ${ww.getName}"
          else
            s"! from ${ww.getName}"
        else
          s"? from ${wr.getName}"
      }
    result
  }

  override def toString: String =
    s"""SyncChan.${this.name} $currentState"""

  /**
   *  Behaves as !(t()) when a reader is already committed
   */
  def offer(t: ()=>T): EventOutcome[Nothing] = {
    if (closed.get) EventOutcome.CLOSED else
      if (full.get)   EventOutcome.FAILED else {
        val peer = reader.get()
        if (peer==null) EventOutcome.FAILED else {
          val value = t()
          this.!(value)
          EventOutcome.SUCCEEDED
        }
      }
  }

  def !(value: T) = {
    checkOpen
    val current = Thread.currentThread
    val lastWriter = writer.getAndSet(current)
    assert(
      lastWriter == null,
      s"[${current.getName}]$name!($value) overtaking [${lastWriter.getName}]$name!($buffer)"
    )
    buffer = value
    full.set(true)
    LockSupport.unpark(reader.get)  // DELIVER BUFFER TO READER
    while (!closed.get && full.get) // AWAIT SYNC FROM READER
    {
      LockSupport.park(this)
    }
    if (full.get) checkOpen         // *** (see close) []
    writer.set(null)                // READY
  }

  /** Behaves as `result.set(?())` when  a writer is already committed */
  def poll(): EventOutcome[T] = {
    if (closed.get)
      EventOutcome.CLOSED
    else
      if (full.get) {
        EventOutcome.POLLED(this.?(()))
      }
      else
        EventOutcome.FAILED
  }

  def ?(t: Unit): T = {
    checkOpen
    val current     = Thread.currentThread
    val lastReader  = reader.getAndSet(current)
    assert(
      lastReader == null,
      s"[${current.getName}]$name?() overtaking [${lastReader.getName}]$name?()"
    )
    while (!closed.get && !full.get)  // AWAIT BUFFER
    {
      LockSupport.park(this)
    }
    checkOpen                       // ** (see close)
    val result = buffer             // ## (cf. ?? at ##)
    buffer = null.asInstanceOf[T]   // For the garbage-collector
    reader.set(null)
    full.set(false)                 // EMPTY BUFFER; READY
    LockSupport.unpark(writer.get)  // SYNC WRITER
    result
  }

  /**
   * Fully close this channel for both input and output.
   */
  override def close(): Unit = {
    if (logging) finer(s"$this CLOSING")
    if (!closed.getAndSet(true)) { // closing is idempotent
      if (logging) finer(s"$this CLOSED")
      LockSupport.unpark( reader.getAndSet(null) ) // Force a waiting reader to continue **
      LockSupport.unpark( writer.getAndSet(null) ) // Force a waiting writer to continue ***
      CSORuntime.removeChannel(this)// Debugger no longer interested
    }
  }


  /** Extended rendezvous read & compute, then sync */
  override def ??[U](f: T => U): U = {
    checkOpen
    val current = Thread.currentThread
    val lastReader = reader.getAndSet(current)
    assert(
      lastReader == null,
      s"[${current.getName}]$name??() overtaking [${lastReader.getName}]$name??()"
    )
    while (!closed.get && !full.get) {
      LockSupport.park(this)
    }
    checkOpen                     // ** (see close)
    val result = f(buffer)        // ## compute before the write sync: (cf. ? at ##)
    buffer = null.asInstanceOf[T] // For the garbage collector
    reader.set(null)
    full.set(false)               // EMPTY BUFFER; READY
    LockSupport.unpark(writer.get)// SYNC WRITER
    result
  }

  def canInput: Boolean = !closed.get

  def closeIn(): Unit = close()

  def canOutput: Boolean = !closed.get

  def closeOut(): Unit = close()

  @inline private[this] def checkOpen =
    if (closed.get) {
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
    checkOpen
    reader.set(current)
    val success =
      0 < Time.parkUntilElapsedOr(this, timeoutNS, closed.get || full.get)
    checkOpen
    val result = buffer
    buffer = null.asInstanceOf[T]
    reader.set(null)
    full.set(false)
    LockSupport.unpark(writer.get)
    if (success) Some(result) else None
  }

  def writeBefore(timeoutNS: Time.Nanoseconds)(value: T): Boolean = {
    assert(
      writer.get == null,
      s"$name!($value) from ${Thread.currentThread.getName} overtaking $name!($buffer) [${writer.get.getName}]"
    )
    checkOpen
    buffer = value
    val current = Thread.currentThread
    writer.set(current)
    full.set(true)
    LockSupport.unpark(reader.getAndSet(null))
    var success =
      0 < Time.parkUntilElapsedOr(this, timeoutNS, closed.get || !full.get)
    if (!success) full.set(false)
    writer.set(null)
    checkOpen
    success
  }

}


/**
 *  A shareable synchronized channel (for completeness) made from an
 *  underlying synchronized channel. Processes
 *  sharing the channel wait (in `Lock` queues) for reading (or writing)
 *  methods to be available. This contrasts with the underlying
 *  channel in which (inadvertently-sharing) readers (writers) MAY (but shouldn't)
 *  overetake each other.
 *
 *  The channel closes at the last invocation of `closeIn`,
 *  or the last invocation of `closeOut`.
 */
class SharedSyncChan[T](name: String, readers: Int=1, writers: Int = 1) extends SyncChan[T](name) {
  import java.util.concurrent.locks.{ReentrantLock => Lock}
  val rLock, wLock = new Lock()

  @inline final def withLock[T](lock: Lock) ( body: => T ): T = {
    lock.lock()
    try { body } catch { case exn: Throwable => throw exn } finally { lock.unlock() }
  }

  val readersLeft = new AtomicLong(readers)
  val writersLeft = new AtomicLong(writers)

  override def toString: String =
    s"SharedSyncChan.$name [readers=${readersLeft.get}/$readers, writers=${writersLeft.get}/$writers] $currentState"

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


/**
 * A simplified sublanguage of ThreadCSO using only
 * virtual threads, and with simplified support for
 * live debugging.
 *
 * Key simplifications:
 *
 * 1. Alternation constructs are simplified, and use direct
 * polling of events in their implementation.
 *
 * 2. A straightforward factory for channels, of which
 * there are four kinds formed along two dimensions:
 *
 *  {{{ synchronised | buffered
 *      shared       | nonshared
 *  }}}
 *
 * @see Chan
 */

package org.sufrin.microCSO

import Alternation._
import termination._
import org.sufrin.logging._
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong



/**
 *  Abstract syntax of a guarded port or channel: precursor of an alternation event
 */
case class GuardedPort[T](guard: () => Boolean, port: AnyPort[T]) {
  def =?=> (f: T=>Unit): `Input-Event`[T]  = `Input-Event`(()=>true, port.asInPort, f)
  def =!=> (value: =>T): `Output-Event`[T]  = `Output-Event`(guard, port.asOutPort, ()=>value)
}






/**
 *  A `proc` is a prototype for an entity that can be applied
 *  or `forked`.  In the former case it runs in the JVM thread from which it
 *  was applied; in the latter case it runs in a newly-acquired
 *  JVM thread.
 *
 *  It can also be composed in parallel with another `proc`.
 *
 *  All `proc`s are made by the factory `proc`.
 */
trait proc extends (()=>Unit) {
   override def toString: String = name

   /** Run in the current thread  */
   def apply(): Unit

  /**
   *  Run in a newly-acquired thread; yielding a handle from which the
   *  thread can be interrupted or awaited.
   */
  def fork():  ForkHandle

  /** Name of the proc */
  def name: String

  /** parallel composition of `this: proc` with `that: proc` */
  def ||(that: proc): proc
 }

import java.util.concurrent.{CountDownLatch => Latch}

class ForkHandle(val name: String, val body: ()=>Unit, val latch: Latch) extends Runnable {
  private val logging: Boolean = false
  override def toString: String =
    s"ForkHandle($name, count=${if(latch==null) "<>" else latch.getCount.toString}){ \n|  status=$status\n|  thread=${thread.getName} }"
  var thread: Thread = null
  var status: Status = UnStarted

  def interrupt(): Unit = if (thread ne null) thread.interrupt()

  def join(): Unit = latch.await()

  override def run(): Unit = {
    var prevName: String = ""
    try {
      thread = Thread.currentThread
      CSORuntime.add(thread)           // add to the database
      prevName = thread.getName
      thread.setName(name)
      status = Running
      body()
      status = Terminated
    } catch {
      case thrown: Anticipated =>
        status = thrown
      case thrown: Throwable   =>
        status = unAnticipated(thrown)
        Default.error(s"[${Thread.currentThread().getName}] threw $thrown")
    } finally {
      thread.setName(prevName)  // remove from the database
      CSORuntime.remove(Thread.currentThread)
    }
    if (logging)
      Default.finest(s"($this).run() => $status")
    if (latch!=null) latch.countDown()
  }

  /** Await termination of this running fork then yield `(true, status)` */
  def terminationStatus(): (Boolean, Status) = {
    if (latch!=null) latch.await()
    (true, status)
  }

  /** Await termination of this running fork for no more than `muSec` microseconds,
   *  then yield `(terminated, status)`, where `terminated`
   *  is true iff the fork terminated by the deadline.
   */
  def terminationStatus(deadline: Time.Nanoseconds): (Boolean, Status) = {
    val terminated =
        if (latch==null) true else latch.await(deadline, TimeUnit.NANOSECONDS)
    (terminated, status)
  }

  /** Acquire a new JVM thread, and evaluate `body()` in it */
  def start(): Unit = {
    thread = Thread.ofVirtual.start( () => run() )
  }
}



class par(components: Seq[proc]) extends proc {
  private val logging: Boolean  = false
  def name: String = components.map(_.name).mkString("(", "||", ")")
  def ||(that: proc): proc = new par(List(this, that))

  def apply(): Unit = {
    val latch       = new Latch(components.length-1)
    val peerHandles = components.tail map {
      proc => new ForkHandle(proc.name, proc, latch)
    }

    val firstHandle = {
      val proc = components.head
      new ForkHandle(proc.name, proc, null)
    }

    for { handle <- peerHandles } { handle.start() }
    // run the first component in the current thread
    firstHandle.run()
    // await the peers
    latch.await()
    // calculate and propagate the status
    var status = firstHandle.status
    for { peer <- peerHandles } {
      status = termination.|||(status, peer.status)
    }
    if (logging) Default.finest(s"$name terminating (${firstHandle.status}, ${peerHandles.map(_.status).mkString(", ")}")
    if (logging) Default.finest(s"|| status was $status") //**
    status.propagate()
  }

  def fork(): ForkHandle = {
    val handle = new ForkHandle(name, apply, new Latch(1))
    handle.start()
    handle
  }
}

/**
 * Implementation of the algebra of terminal `Status`es that is used to support
 * coherent termination of `||` constructs. `||(P1, ... Pn)` constructs a proc
 * that (when started) terminates when all its components have terminated. Its terminal
 * status is the least upper bound of the terminal statuses of its components (in
 * the ordering `unAnticipated >= Anticipated >= Terminated`). This
 * supports the "outward" propagation of non-`Terminated` statuses.
 * // TODO: It may, at some point, be useful to preserve the detail of
 *          the termination statuses; in which case another constructor
 *          can be added.
 */
object termination {
  private val logging: Boolean = false
  trait Status {
    /** LUB of `this` and `other` */
    def |||(other: Status):Status = termination.|||(this, other)
    def propagate(): Unit
  }

  lazy val Stopped: Anticipated = new Anticipated("stop")

  /**
   *  @return an LUB of `l, and `r`
   */
  def |||(l: Status, r: Status): Status = {
    (l, r) match {
      case (_:unAnticipated, _)               => l
      case (_,               _:unAnticipated) => r
      case (_:Anticipated,   _)               => l
      case (_,               _:Anticipated)   => r
      case (Terminated,      r)               => r
      case (l,               Terminated)      => l
    }
  }

  case class unAnticipated(throwable: Throwable) extends Status {
    override def toString: String = s"unAnticipated($throwable)"
    def propagate(): Unit = {
      if (logging) {
        if (Default.level>=FINEST) {
          Default.finest(s"[${Thread.currentThread().getName}]$this.propagate()")
          ThreadTracing.showStackTrace(throwable.getStackTrace)
          System.out.flush()
        }
      }
      throw throwable
    }
  }

  class Closed(name: String) extends Anticipated(s"Closed($name)") {
    override def toString: String = s"Closed($name)"
    override def propagate(): Unit = {
      if (logging) Default.finest(s"$this.propagate()")
      throw this
    }
  }

  class Anticipated(why: =>String)  extends Throwable with Status {
    override def toString: String = s"$why"
    override def propagate(): Unit = {
      if (logging) Default.finest(s"$this.propagate()")
      throw this
    }
  }

  case object Terminated extends Status {
    override def propagate(): Unit =
      if (logging) Default.finest(s"$this.propagate()")
      ()
  }

  case object Running extends Status {
    override def propagate(): Unit = throw new Exception("Running must not propagate")
  }

  case object UnStarted extends Status {
    override def propagate(): Unit = throw new Exception("Unstarted must not propagate")
  }

}

trait serialNamer {
  val serial: AtomicLong = new AtomicLong(0)
  val namePrefix: String
  def nextName(): String = s"$namePrefix#${serial.getAndAdd(1)}"
}

object proc extends serialNamer with Control {

  import termination._
  val namePrefix = "proc"

  def logStatus(status: Status): Unit = {
    if (logging) Default.finest(s"(${Thread.currentThread().getName})() ==> $status")
    if (logging) {
      status match {
        case unAnticipated(throwable: Throwable) =>
          throwable.printStackTrace()
        case Terminated =>
        case throwable: Anticipated =>
          throwable.printStackTrace()
      }
    }
  }

  def apply(name: String)(body: => Unit): proc = new PROCESS(name, ()=>body)

  def apply(body: => Unit): proc = new PROCESS(nextName(), ()=>body)

  /**
   *  Constructor for a simple `proc`
   */
  private class PROCESS(val name: String="", val body: ()=>Unit)  extends proc { thisProc =>
    import termination._
    private val logging: Boolean = false

    def apply(): Unit = body()

    def fork(): ForkHandle = {
      val handle = new ForkHandle(name, apply, new Latch(1))
      handle.start()
      handle
    }

    /**
     *  A `proc` that runs `this`  and `that` in parallel until they have both
     *  terminated, then propagates an appropriately informative termination status.
     *
     *  @see Status.|||
     */
    def ||(that: proc): proc = new par(List(this, that))


    /** Syntactic sugar: coerce a closure to a proc  */
    def ||(body: => Unit): proc = new par(List(this, proc { body }))

  }
}



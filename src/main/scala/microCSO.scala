
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


trait Closeable {
  /** Close this, and give up any of its resources */
  def close(): Unit
}


trait OutPort[T] extends Closeable { port =>
  /**
   * Idempotent promise that there will be no further writes to this channel.
   * Buffered channels may nevertheless be capable of further reads.
   */
  def closeOut(): Unit
  def close(): Unit = closeOut()

  def writeBefore(timeoutNS: Time.Nanoseconds)(value: T): Boolean
  def !(t: T): Unit

  /**
   * Behaves as `!(t())` and yields `FAILED` if something can be accepted by the channel; else
   * yields `NO` or `CLOSED`.
   */
  def offer(t: () => T): EventOutcome[Nothing]

  def out: OutPort[T] = port

  /** This port hasn't yet been closed */
  def canOutput: Boolean


  /**
   * OutPort event notation
   */
  def && (guard: => Boolean): GuardedPort[T] = GuardedPort[T](()=>guard, `Out-Port`(out))
  def =!=> (value: =>T): `Output-Event`[T]  = `Output-Event`(()=>true, out, ()=>value)
}


trait InPort[T] extends Closeable { port: InPort[T] =>
  /** Idempotent promise that there will be no further reads from the associated channel */
  def closeIn(): Unit
  def close(): Unit = closeIn()
  def readBefore(timeOut: Time.Nanoseconds): Option[T]
  /** read from the associated channel */
  def ?(t: Unit): T
  /** read from the associated channel and apply `f` to the result */
  def ?[V](f: T=>V): V = f(this.?(()))
  /**
   *   Extended-rendezvous read: return to the writer only when `f` terminates.
   *   This is distinct from `?[V](f: T=>V): V` only in synchronized channels.
   */
  def ??[V](f: T=>V): V = f(this.?(()))
  /** this port itself */
  def in: InPort[T] = port

  /**
   * Yields `RETURN(?())` when a writer to the port's channel has already committed to output
   * else yields `CLOSED` or `NO`
   */
  def poll(): EventOutcome[T]

  /** This port hasn't yet been closed */
  def canInput: Boolean

  def isSharedInPort: Boolean = false

  /**
   *  InPort event notation
   */
  def && (guard: => Boolean): GuardedPort[T] = GuardedPort[T](()=>guard, `In-Port`(in))
  def =?=> (f: T=>Unit): `Input-Event`[T]  = `Input-Event`(()=>true, in, f)
}

/**
 *  Abstract syntax of a guarded port or channel: precursor of an alternation event
 */
case class GuardedPort[T](guard: () => Boolean, port: AnyPort[T]) {
  def =?=> (f: T=>Unit): `Input-Event`[T]  = `Input-Event`(()=>true, port.asInPort, f)
  def =!=> (value: =>T): `Output-Event`[T]  = `Output-Event`(guard, port.asOutPort, ()=>value)
}




/** Database of running processes, and channels   */
object CSORuntime {
  def reset(): Unit = {
    vThreads.clear()
    vChannels.clear()
  }
  /** Mapping of (running) thread ids to CSORuntime */
  val vThreads =
    new scala.collection.concurrent.TrieMap[Long, Thread]

  val vChannels =
    new scala.collection.concurrent.TrieMap[Int, AnyRef]

  /** Evaluate f at each of the running threads  */
  def forEach(f: Thread=>Unit): Unit =
    vThreads.foreach{ case (_, thread) => f(thread)}

  /** remove from the database (when terminating) */
  def remove(thread: Thread): Unit = {
    vThreads.remove(thread.threadId)
    thread.getState match {
      case Thread.State.TERMINATED => removeLocals(thread)
      case _ =>
    }
  }

  /** add to the database (when starting) */
  def add(thread: Thread): Unit = vThreads += (thread.threadId -> thread)

  /** Evaluate f at each of the running threads  */
  def forEachChannel(f: Chan[Any] => Unit): Unit =
    vChannels.foreach{ case (_, chan) => f(chan.asInstanceOf[Chan[Any]])}

  /** remove from the database (when terminating) */
  def removeChannel(chan: Chan[_]): Unit = vChannels.remove(System.identityHashCode(chan))

  /** add to the database (when starting) */
  def addChannel(chan: Chan[_]): Unit = vChannels += (System.identityHashCode(chan) -> chan)

  //////////////////////// local variables

  type LocalKey = String
  type LocalThunk = ()=> Any

  val vLocals =
    new scala.collection.concurrent.TrieMap[Long, scala.collection.concurrent.TrieMap[LocalKey, LocalThunk]]

  def newLocal[V](key: String, value: =>V): Unit = {
    val id  = Thread.currentThread().threadId
    val map = vLocals.getOrElseUpdate(id, new scala.collection.concurrent.TrieMap[LocalKey, LocalThunk])
    map += (key -> { () => value })
  }

  def forLocals(thread: Thread)(fun: (String, Any)=>Unit): Unit = {
    val id  = thread.threadId
    for { map <- vLocals.get(id) }
        for { (k, thunk) <- map } fun(k, thunk())
  }

  def removeLocals(thread: Thread): Unit = {
    val id  = thread.threadId
    for { map <- vLocals.get(id) } map.clear()
    vLocals.remove(id)
  }
}

object Threads {
  import java.io.PrintStream

  val suppress: String = "java.base"


  def showThreadTrace(thread: Thread, out: PrintStream) = {
    out.println(thread)
    CSORuntime.forLocals(thread) {
      case (key, value) => out.println(f"$key%8s -> $value%s")
    }
    showStackTrace(thread.getStackTrace, out)
  }

  def showStackTrace(trace: Array[StackTraceElement], out: PrintStream=System.out) = {
    for (frame <- trace
         if ! frame.isNativeMethod
        )
    {
      if (frame.getClassName.startsWith("java.")) {
      }
      else
        out.println(unmangle(frame.toString))
    }
    out.println()
  }

  def showThreadTrace(thread: Thread): Unit =
      showThreadTrace(thread: Thread, System.out)

  /** Mapping from mangleable characters to their mangling. */
  private val mangleMap = List(
    ("~", "$tilde"),
    ("=", "$eq"),
    ("<", "$less"),
    (">", "$greater"),
    ("!", "$bang"),
    ("#", "$hash"),
    ("%", "$percent"),
    ("^", "$up"),
    ("&", "$amp"),
    ("|", "$bar"),
    ("*", "$times"),
    ("/", "$div"),
    ("+", "$plus"),
    ("-", "$minus"),
    (":", "$colon"),
    ("\\", "$bslash"),
    ("?", "$qmark"),
    ("@", "$at")
  )

  /** unmangle a compiler-generated mangled name */
  private def unmangle(name: String): String = {
    var r = name
    for ((ch, mangled) <- mangleMap) r = r.replace(mangled, ch)
    r
  }
}

/**
 *  A `process` is a prototype for an entity that can be applied
 *  or `forked`.  In the former case it runs in the JVM thread from which it
 *  was applied; in the latter case it runs in a newly-acquired
 *  JVM thread.
 *
 *  It can also be composed in parallel with another `process`.
 */
trait process extends (()=>Unit) {
   override def toString: String = name

   /** Run in the current thread  */
   def apply(): Unit
  /**
   *  Run in a newly-acquired thread; yielding a handle from which the
   *  thread can be interrupted or awaited.
   */
  def fork():  ForkHandle

  /** Name of the process */
  def name: String

  /** parallel composition of `this: process` with `that: process` */
  def ||(that: process): process
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

/**
 *  Constructor for a simple `process`
 */
class proc(val name: String="", val body: ()=>Unit)  extends process { thisProc =>
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
   def ||(that: process): process = new par(List(this, that))


  /** Syntactic sugar: coerce a closure to a proc  */
  def ||(body: => Unit): process = new par(List(this, proc { body }))

}

class par(components: Seq[process]) extends process {
  private val logging: Boolean  = false
  def name: String = components.map(_.name).mkString("(", "||", ")")
  def ||(that: process): process = new par(List(this, that))

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
 * coherent termination of `||` constructs. `||(P1, ... Pn)` constructs a process
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
          Threads.showStackTrace(throwable.getStackTrace)
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

object proc extends serialNamer {
  private val logging: Boolean = false

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

  def apply(name: String)(body: => Unit): proc = new proc(name, ()=>body)

  def apply(body: => Unit): proc = new proc(nextName(), ()=>body)

  def stop: Unit = throw termination.Stopped
  def fail(why: String): Unit = throw new Error(s"fail($why) from ${Thread.currentThread.getName}")

  def ||(procs: Seq[process]): process = new par(procs)
  def ||(proc: process, procs: process*): process = new par(proc::procs.toList)


  /** Iterate `body` while the evaluation of `guard` yields `true`. If an
   * exception ''ex'' is thrown, then stop the iteration; and then unless ''ex'' is
   * a `Anticipated` re-throw ''ex''.
   */
  def repeat(guard: => Boolean)(body: => Unit): Unit = {
    var go = guard
    while (go)
      try {
        body; go = guard
      }
      catch {
        case a: Anticipated => if (logging) Default.finest(s"repeat => $a"); go = false
        case t: Throwable   => throw t
      }
  }

  def repeatedly(body: => Unit): Unit = {
    var go = true
    while (go)
      try {
        body
      }
      catch {
        case a: Anticipated => if (logging) Default.finest(s"repeatedly => $a"); go = false
        case t: Throwable => throw t
      }
  }

  /** Evaluate `body` and return its value unless an exception ''ex'' is thrown.
   * If ''ex'' is a `Anticipated` then evaluate and return the value of
   * `alternative`, otherwise re-throw ''ex''.
   */
  def attempt[T](body: => T)(alternative: => T): T = {
    try {
      body
    }
    catch {
      case _: Anticipated => alternative
      case t: Throwable   => throw t
    }
  }


  /** `repeatFor (it: Iterable[T]) { bv => body }` applies the function `{ bv =>
   * body }` to each of the elements of an iterator formed from the iterable.
   * If an exception ''ex'' is thrown, then stop the iteration; then unless
   * ''ex'' is a `Anticipated` re-throw ''ex''.
   */
  def repeatFor[T](iterable: Iterable[T])(body: T => Unit): Unit =
    attempt {
      for (t <- iterable) body(t)
    } {}

  /**
   *  Evaluate `body` then yield its result after closing all
   *  the listed ports
   */
  def withPorts[T](ports: Closeable*)(body: =>T): T = {
    var result = null.asInstanceOf[T]
    try {
      result = body
    } catch {
      case exn: Throwable => throw exn
    }
    finally {
      for { port <-ports } port.close()
    }
    result
  }
}

object Component extends Loggable{
  import proc._

  /** Copy from the given input stream to the given output streams, performing
   * the outputs concurrently. Terminate when the input stream or any of the
   * output streams is closed.
   * {{{
   * in            /|----> x, ...
   * x, ... >---->{ | : outs
   *               \|----> x, ...
   * }}}
   */
  @inline
  def tee[T](in: InPort[T], outs: OutPort[T]*): process = Tee(in, outs)
  def Tee[T](in: InPort[T], outs: Seq[OutPort[T]]): process = proc("tee") {
    var v       = null.asInstanceOf[T]
    val outputs = ||(for (out <- outs) yield proc { out ! v })
    repeatedly { v = in ? (); outputs() }
    in.closeIn()
    for (out <- outs) out.closeOut()
  }

  def zip[A,B](as: InPort[A], bs: InPort[B])(out: OutPort[(A,B)]): proc = proc(s"zip($as,$bs)($out)") {
    var a = null.asInstanceOf[A]
    var b = null.asInstanceOf[B]
    val read = proc(s"$as?()") {
      a = as ? ()
    } || (proc(s"$bs?()") {
      b = bs ? ()
    })
    withPorts(as, bs, out) {
      repeatedly {
        read()
        out ! (a, b)
      }
    }
  }

  def zip[A,B,C](as: InPort[A], bs: InPort[B], cs: InPort[C])(out: OutPort[(A,B,C)]): proc = proc(s"zip($as,$bs,$cs)($out)") {
    var a = null.asInstanceOf[A]
    var b = null.asInstanceOf[B]
    var c = null.asInstanceOf[C]
    val read =
        ||(proc(s"$as?()") { a = as ? () },
           proc(s"$bs?()") { b = bs ? () },
           proc(s"$cs?()") { c = cs ? () })
    withPorts(as, bs, cs, out) {
      repeatedly {
        read()
        out ! (a, b, c)
      }
    }
  }

  def copy[T](in: InPort[T], out: OutPort[T]): process = proc(s"copy($in, $out)") {
    withPorts(in, out) {
      repeatedly {
        out ! (in ? ())
      }
    }
  }

  def merge[T](ins: Seq[InPort[T]])(out: OutPort[T]): process  = proc (s"merge($ins)($out)") {
      Serve(
        for { in<-ins } yield in =?=> { t => out!t }
      )
    if (logging) finer(s"merge($ins)($out) SERVE terminated")
    out.closeOut()
    for { in<-ins } in.closeIn()
    if (logging) finer(s"merge($ins)($out) terminated after closing")
  }

  def source[T](out: OutPort[T], it: Iterable[T]): proc = proc (s"source($out,...)"){
      var count = 0
      //CSORuntime.newLocal("source count", count)
      //CSORuntime.newLocal("source out", out)
      repeatFor (it) { t => out!t; count += 1 }
      out.closeOut()
      if (logging) finer(s"source($out) closed")
  }

  def sink[T](in: InPort[T])(andThen: T=>Unit): proc = proc (s"sink($in)"){
      var count = 0
      //CSORuntime.newLocal("sink count", count)
      //CSORuntime.newLocal("sink in", in)
      repeatedly { in?{ t => andThen(t); count+=1 } }
      in.closeIn()
      if (logging) finer(s"sink($in) closed")
  }
}


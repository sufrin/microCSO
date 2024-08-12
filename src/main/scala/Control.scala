package org.sufrin.microCSO

import termination.Anticipated
import org.sufrin.logging.{Default, Loggable}


/** Definitions of the sequential control constructs */
trait Control extends Loggable {
  /** Terminate the (closest, dynamic) repetition or process */
  def stop: Unit = throw termination.Stopped

  /** Fail within the (closest, dynamic) repetition or process  */
  def fail(why: String): Unit = throw new Error(s"fail($why) from ${Thread.currentThread.getName}")

  /** A `procs.length`-ary concurrent composition */
  def ||(procs: Seq[proc]): proc = new par(procs)

  /** Concurrent composition of all the `proc`-typed arguments; of which there must be at least one. */
  def ||(proc: proc, procs: proc*): proc = new par(proc::procs.toList)

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

  /**
   *  Iterate `body` indefinitely or until an
   *  exception ''ex'' is thrown; and then unless ''ex'' is
   *  an `Anticipated` re-throw ''ex''.
   */
  def repeatedly(body: => Unit): Unit = {
    var go = true
    while (go)
      try {
        body
      }
      catch {
        case a: Anticipated => if (logging) finest(s"repeatedly => $a"); go = false
        case t: Throwable => throw t
      }
  }

  /** Evaluate `body` and return its value unless an exception ''ex'' is thrown.
   * If ''ex'' is an `Anticipated` then evaluate and return the value of
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


  /** {{{repeatFor (it: Iterable[T]) { bv => body } }}} applies the function
   * {{{ { bv => body } }}} to each of the elements of an iterator formed from the iterable.
   * If an exception ''ex'' is thrown, then stop the iteration; then unless
   * ''ex'' is an `Anticipated` re-throw ''ex''.
   */
  def repeatFor[T](iterable: Iterable[T])(body: T => Unit): Unit =
    attempt {
      for (t <- iterable) body(t)
    } {}

  /**
   *  Evaluate `body` then yield its result after closing all
   *  the listed ports.
   */
  @inline def withPorts[T](ports: Closeable*)(body: =>T): T = WithPorts(ports)(body)

  /**
   *  Evaluate `body` then yield its result after closing all
   *  the listed ports.
   */
  def WithPorts[T](ports: Seq[Closeable], otherPorts: Closeable*)(body: =>T): T = {
    var result = null.asInstanceOf[T]
    try {
      result = body
    }
    finally {
      if (Component.logging) {
        for { port<-ports } Component.finer(s"WithPorts $port.close()")
      }
      for { port <-ports } port.close()
      for { port <-otherPorts } port.close()
    }
    result
  }

}

package org.sufrin.microCSO

/**
 * Port traits
 */

import Alternation.{EventOutcome, `In-Port`, `Input-Event`, `Out-Port`, `Output-Event`}

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
  /** An unspecified element of `T`, intended for variable initialization.*/
  val nothing: T = null.asInstanceOf[T]

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

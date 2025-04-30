package org.sufrin.microCSO


import java.util.concurrent.CyclicBarrier

/**
 * A quick-and-dirty (ie slow and dirty) implementation of barrier synchronization.
 * Its only advantage over  `LogBarrier` and `CombiningLogBarrier` is that processes
 * invoking sync need not have (integer identities). And even that advantage can be finessed
 * away by given the log barriers "enlistment" protocols. We'll do that eventually.
 */
class Barrier(arity: Int) extends CyclicBarrier(arity) {
  def sync(): Unit = await()
}

/** A `LogBarrier(n)` supports the ''repeated'' synchronization of `n` processes,
 * each with a distinct identity, `0<=id<n`.
 *
 * If `b` is such a barrier then `b.sync(id)` calls are stalled until all `n`
 * identities have been accounted for.
 *
 * When `n==1` then `b.sync(0)` returns immediately: this is so multi-worker
 * structures can be tested with only a single-worker, and may be helpful when
 * testing cellular automata.
 *
 * This implementation sets up a tree-shaped network of 2N semaphores, and
 * each `sync(id)` requires 2 communications between the process `id` and each of
 * its (up to) two children. Under favourable conditons some of the communications
 * can take place in parallel.
 *
 */

class LogBarrier(n: Int, _name: String = null) {
  assert(n >= 1)
  val name = if (_name==null) s"LogBarrier($n)" else _name
  /** thread signals parent that it's ready */
  val ready = Array.fill(n)(Semaphore(false))
  /** parent signals thread that it can proceed */
  val go    = Array.fill(n)(Semaphore(false))

  @inline private def left(id: Int): Int  = 1+2*id
  @inline private def right(id: Int): Int = 2+2*id

  def sync(id: Int): Unit = {
    val l = left(id)
    val r = right(id)

    // await both children
    if (l<n) ready(l).acquire()
    if (r<n) ready(r).acquire()
    // children ready
    if (id != 0) {
      ready(id).release() // tell parent
      go(id).acquire()    // await parent's signal
    }
    if (l<n) go(l).release()
    if (r<n) go(r).release()
  }
}

class CombiningLogBarrier[T](n: Int, e: T, op: (T, T) => T, _name: String = null) {
  val name = if (_name==null) s"CombiningLogBarrier($n)" else _name

  assert(n >= 1)
  private var r, g = -1

  // Remark: Synchronization of the ready and go slots is not necessary; since no
  // entitities external to the reader and writer of each channel are involved,
  // Here a write always happens before the corresponding read, and the subsequent
  // write (if any) must happen-after that read.
  /**
   * Thread signals parent that it's ready
   */
  val ready = Array.fill(n)({ r+=1; new Slot[T](s"$name.ready($r)") })

  /**
   * Parent signals thread that it can proceed
   */
  val go    = Array.fill(n)({ g+=1; new Slot[T](s"$name.go($r)")})

  @inline private def left(id: Int): Int  = 1+2*id
  @inline private def right(id: Int): Int = 2+2*id

  def sync(id: Int)(myResult: T): T = {
    val l = left(id)
    val r = right(id)
    var result: T = myResult
    // await both children and incorporate their answers
    if (l<n) result = op(result, ready(l).read())
    if (r<n) result = op(result, ready(r).read())
    // children ready
    if (id != 0) {
      ready(id).write(result) // tell parent
      result = go(id).read()  // await parent's result
    }
    if (l<n) go(l).write(result)
    if (r<n) go(r).write(result)
    result
  }
}


/**
 * An initially-EMPTY high-performance, no-frills, one-slot buffer
 */

class Slot[T](name: String="") {
  override def toString: String = s"DataChan($name)"
  val isFull  = Semaphore(false)
  val isEmpty = Semaphore(true)

  var buffer: T = _

  def read(): T = {
    isFull.acquire()
    val res = buffer
    buffer = null.asInstanceOf[T]
    isEmpty.release()
    res
  }

  def write(datum: T) = {
    isEmpty.acquire()
    buffer = datum
    isFull.release()
  }

}

object Semaphore {
  import java.util.concurrent.Semaphore
  def apply(available: Boolean): Semaphore = new Semaphore(if (available) 1 else 0)
  def apply(available: Int): Semaphore = new Semaphore(available)
}
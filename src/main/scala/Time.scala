package org.sufrin.microCSO

object Time {
  type Nanoseconds = Long
  type Milliseconds = Long

  /** Number of nanoseconds in a nanosecond */
  val nanoSec: Nanoseconds = 1L

  /** Number of nanoseconds in a microsecond: `n*microSec` is n microseconds
   * expressed as nanoseconds
   */
  val microSec: Nanoseconds = 1000L * nanoSec

  /** Number of nanoseconds in a microsecond: `n*μS` is n microseconds expressed
   * as nanoseconds
   */
  val μS: Nanoseconds = microSec

  /** Number of nanoseconds in a millisecond: `n*milliSec` is n milliseconds
   * expressed as nanoseconds
   */
  val milliSec: Nanoseconds = 1000L * microSec

  /** Number of nanoseconds in a second: `n*Sec` is n seconds expressed as
   * nanoseconds
   */
  val Sec: Nanoseconds = 1000L * milliSec

  /** Number of nanoseconds in a minute: `n*Min` is n minutes expressed as
   * nanoseconds
   */
  val Min: Nanoseconds = 60L * Sec

  /** Number of nanoseconds in an hour: `n*Hour` is n hours expressed as
   * nanoseconds
   */
  val Hour: Nanoseconds = 60L * Min

  /** Number of nanoseconds in a day: `n*Day` is n days expressed as nanoseconds
   */
  val Day: Nanoseconds = 24L * Hour

  /** Convert a fractional time expressed in seconds to nanoseconds */
  def seconds(secs: Double): Nanoseconds = (secs * Sec).toLong

  /** Sleep for the given number of milliseconds. */
  @inline def sleepms(ms: Milliseconds): Unit = Thread.sleep(ms)

  /** Sleep for the given number of nanoseconds */
  @inline def sleep(ns: Nanoseconds): Unit =
    Thread.sleep(ns / milliSec, (ns % milliSec).toInt)

  /** Read the system nanosecond timer */
  @inline def nanoTime: Nanoseconds = System.nanoTime()

  /** Read the system millisecond timer */
  @inline def milliTime: Milliseconds = System.currentTimeMillis()

  /** Wait until `deadline` for `condition` to become true. If it became true
   * before the deadline then the result is the time remaining when it became
   * true. Otherwise the result will be negative, and representing the time
   * after the deadline when deadline expiry was noticed.
   *
   * @param blocker
   *   the object to be reported as the blocker by debuggers
   * @param deadline
   *   the deadline in nanoseconds
   * @param condition
   *   the condition
   * @return
   *   Nanoseconds remaining when the condition became true or when the
   *   deadline expired (possibly negative)
   */
  @inline def parkUntilDeadlineOr(
                                   blocker: AnyRef,
                                   deadline: Nanoseconds,
                                   condition: => Boolean
                                 ): Nanoseconds = {
    var left = deadline - System.nanoTime
    while (left > 0 && !condition) {
      java.util.concurrent.locks.LockSupport.parkNanos(blocker, left)
      left = deadline - System.nanoTime
    }
    // left<=0 || condition
    return left
  }

  /** Equivalent to `parkUntilDeadline(blocker, timeOut+System.nanoTime,
   * condition)`
   */
  @inline def parkUntilElapsedOr(
                                  blocker: AnyRef,
                                  timeOut: Nanoseconds,
                                  condition: => Boolean
                                ): Nanoseconds = {
    val deadline = timeOut + System.nanoTime
    var left = timeOut
    while (left > 0 && !condition) {
      java.util.concurrent.locks.LockSupport.parkNanos(blocker, left)
      left = deadline - System.nanoTime
    }
    // left<=0 || condition
    return left
  }


}

package org.sufrin.microCSO

import org.sufrin.microCSO.Time.{microSec, Nanoseconds}
import org.sufrin.microCSO.proc.{repeatedly, stop}

object Alternation  {
  private final val nanoDelta: Nanoseconds = 5*microSec


  /** Possible outcome of an alternation event  */
  trait  EventOutcome [+T] {}
  object EventOutcome {
    case class  POLLED[T](value: T) extends EventOutcome[T] // for a poll()
    case object SUCCEEDED extends EventOutcome[Nothing]
    case object CLOSED extends EventOutcome[Nothing]
    case object FAILED extends EventOutcome[Nothing]
  }

  /** Encoding of the port or channel that guards an event: abstract syntax */
  trait AnyPort[T] {
    def asInPort:  InPort[T]  = null
    def asOutPort: OutPort[T] = null
  }
  case class `Out-Port`[T](port: OutPort[T]) extends AnyPort[T] { override def asOutPort = port }
  case class `In-Port`[T](port: InPort[T])   extends AnyPort[T] { override def asInPort: InPort[T] = port }
  case class `Both-Ports`[T](chan: Chan[T])  extends AnyPort[T] {
    override def asInPort: InPort[T]   = chan.in
    override def asOutPort: OutPort[T] = chan.out
  }

  /** Events constructed by the alternation notation */
  trait IOEvent
  case class `Input-Event`[T](guard: () => Boolean, port: InPort[T], f: T => Unit) extends IOEvent
  case class `Output-Event`[T](guard: () => Boolean, port: OutPort[T], f: () => T) extends IOEvent
  case class `Or-Else`(eval: ()=>Unit)
  case class `After-NS`(ns: Nanoseconds, eval: ()=>Unit)

  import EventOutcome._

  /**
   *  Repeatedly find and fire one of the `events`, until
   *  none are feasible. The search for firing events is in
   *  sequential order: it is "fair" inasmuch as
   *  at each iteration `events` is rotated by a single place.
   *
   * @see ServeBefore
   */
  def Serve(events: Seq[IOEvent]): Unit = {
    val fairness = new Rotater(events)
    repeatedly {
      pollEvents(fairness, deadline=0, afterDeadline = { ()=>}) match {
        case true  => fairness.rot()
        case false => stop
      }
    }
  }
  /**
   *  Repeatedly find and fire one of the `events` until
   *  none are feasible. The search for firing events is in
   *  sequential order: it is "fair" inasmuch as
   *  at each iteration `events` is rotated by a single place.
   *
   * The "finding" is performed by polling every `waitDelta` nanoseconds until
   * {{{
   *   (a) one of the `events` has fired -- then rotate `events` and continue serving
   *   (b) the deadline (if positive) has expired -- then evaluate `afterDeadline` and continue serving
   *   (c) none of the `events` are feasible -- then evaluate `orElse`, and stop
   * }}}
   *
   * @see pollEvents
   */
  def ServeBefore(deadline: Nanoseconds=0, afterDeadline: => Unit = {}, orElse: => Unit = {}, waitDelta: Nanoseconds = nanoDelta )(events: Seq[IOEvent]): Unit = {
    val fairness = new Rotater(events)
    repeatedly {
      pollEvents(fairness, deadline, afterDeadline = { ()=>afterDeadline}) match {
        case true  =>
          fairness.rot()
        case false =>
          orElse
          stop
      }
    }
  }

  /**
   * @see ServeBefore
   */
  def serveBefore(deadline: Nanoseconds=0, afterDeadline: => Unit = {}, orElse: => Unit = {}, waitDelta: Nanoseconds = nanoDelta )(events: IOEvent*): Unit = {
    val fairness = new Rotater(events)
    repeatedly {
      pollEvents(fairness, deadline, afterDeadline = { ()=>afterDeadline}) match {
        case true  =>
          fairness.rot()
        case false =>
          orElse
          stop
      }
    }
  }

  /**
   *  Repeatedly find one of the `events` that fires, until
   *  none are feasible. The search for firing events is in
   *  sequential order: it is "fair" inasmuch as
   *  at each iteration `events` is rotated by a single place.
   *
   * @see serveBefore
   */
  def serve(events: IOEvent*): Unit = Serve(events)

  /**
   * Poll periodically until one of the `events` fires or none is feasible.
   * In the latter case an error is thrown.
   *
   * @see altBefore
   */
  def alt(events: IOEvent*): Unit = Alt(events)

  /**
   * Poll periodically until one of the `events` fires or none is feasible. In
   * the latter case an error is thrown.
   *
   * @see AltBefore
   */
  def Alt(events: Seq[IOEvent]): Unit =
    pollEvents(events, deadline=0, afterDeadline = { ()=>})  match {
      case false => throw new Error("alternation: no feasible events")
      case true =>
    }

  /**
   * Poll every `waitDelta` nanoseconds until
   * {{{
   *   (a) one of the `events` has fired
   *   (b) the deadline (if positive) has expired -- then evaluate `afterDeadline`
   *   (c) none of the `events` are feasible -- then evaluate `orElse`.
   * }}}
   *
   * @see pollEvents
   */
  def altBefore(deadline: Nanoseconds=0, afterDeadline: => Unit = {}, orElse: => Unit = {} , waitDelta: Nanoseconds = nanoDelta)(events: IOEvent*): Unit =
    pollEvents(events, deadline, afterDeadline = { ()=>afterDeadline }, waitDelta) match {
      case false => orElse
      case true  =>
    }

  /**
   * Poll every `waitDelta` nanoseconds until
   * {{{
   *   (a) one of the `events` has fired
   *   (b) the deadline (if positive) has expired -- then evaluate `afterDeadline`
   *   (c) none of the `events` are feasible -- then evaluate `orElse`
   * }}}
   *
   * @see pollEvents
   *
   */
  def AltBefore(deadline: Nanoseconds=0, afterDeadline: => Unit = {}, orElse: => Unit = {}, waitDelta: Nanoseconds = nanoDelta )(events: Seq[IOEvent]): Unit =
    pollEvents(events, deadline, afterDeadline = { ()=>afterDeadline }, waitDelta) match {
      case false => orElse
      case true  =>
    }

  /**
   *  Poll until one of `events`  fires or none are feasible.
   *  Yield true in the former case and false in the latter case.
   *
   *  If there's a positive `deadline` then
   *  evaluate `afterDeadline()` and return true if none has fired before the
   *  deadline has elapsed; else just continue (busy) waiting.
   *
   *  `waitDelta` is the delay between successive attempts to find and fire an event.
   *
   * A more elegant solution replaces the polling. Each running alternation
   * that has finds no fireable event on the first poll, has informed all the channels
   * of its feasible events that it is waiting (by passing a shared semaphore, on which it is waiting, to each of them).
   * A channel that is holding such a semaphore just releases it when anything interesting has happened to it
   * (closed, poll would succeed, offer would succeed). The downside of this method is that all the channels that
   * were involved must now have their semaphores removed by a closing phase of the alternation.
   *
   */
  def pollEvents(events: Seq[IOEvent], deadline: Nanoseconds=0, afterDeadline: ()=>Unit, waitDelta: Nanoseconds = nanoDelta): Boolean = {
    var result  = false
    var waiting = true
    var remainingTime = deadline
    val hasDeadline   = remainingTime>0

    while (waiting) {
      val (feasibles,  fired) = fireFirst(events)
      // result is FAILED or all events refused or infeasible
      fired match {
        case SUCCEEDED =>
          // an event fired
          result = true
          waiting = false
        case CLOSED | FAILED =>
          // no event seen on this pass fired
          if (feasibles == 0) {
            // now nothing CAN happen
            // print("* "); System.out.flush()
            result = false
            waiting = false
          } else if (hasDeadline && remainingTime<=0) {
            afterDeadline()
            result = true
            waiting = false
          } else {
            // wait a bit and try again
            Time.sleep(waitDelta)
            remainingTime -= waitDelta
          }
      }
    }
    result
  }

  /**
   * Find and fire the first of the (input or output) `events` that is ready.
   * Return the number of feasible events, with one of `SUCCEEDED|CLOSED|FAILED`; an
   * input event that successfully polled is treated as `SUCCEEDED`
   */
  @inline private final def fireFirst(events: Seq[IOEvent]): (Int, EventOutcome[Nothing]) = {
    var outcome:  EventOutcome[Nothing] = FAILED
    var feasible: Int = 0
    val feasibles = events.filter{
      case `Output-Event`(guard, port, _) => guard() && port.canOutput
      case `Input-Event`(guard, port, _) => guard() && port.canInput
    }
    //if (feasibles.isEmpty) Default.finer(s"fireFirst ${feasibles.length} feasible")
    val iter: Iterator[IOEvent] = feasibles.iterator
    // find and fire the first ready event
    while (outcome!=SUCCEEDED && iter.hasNext) {
      iter.next() match {
        case `Output-Event`(guard, port, f) =>
          outcome = port.offer(f)
          outcome match {
            case SUCCEEDED =>
              feasible += 1
            case CLOSED =>
              outcome = CLOSED
            case FAILED     =>
              outcome = FAILED            }
        case `Input-Event`(guard, port, f) =>
          port.poll() match {
            case POLLED(result)    =>
              f(result)
              outcome = SUCCEEDED
              feasible += 1
            case CLOSED =>
              outcome = CLOSED
            case FAILED     =>
              outcome = FAILED
          }
      }
    }
    (feasibles.length, outcome)
  }

  class Rotater[T](val original: Seq[T]) extends Seq[T] {
    val length: Int = original.length
    private val LENGTH = length
    private var offset: Int = 0
    def rot(): Unit = { offset = (offset+1) % LENGTH}

    def iterator: Iterator[T] = new Iterator[T] {
      var current: Int = offset
      var count : Int  = LENGTH
      def hasNext: Boolean = count>0

      def next(): T = {
        val r = original(current)
        current = (current + 1) % LENGTH
        count -=1
        r
      }
    }

    def apply(i: Int): T = original((offset+i)%LENGTH)
  }
}

package org.sufrin.microCSO

import org.sufrin.logging.Loggable
import org.sufrin.microCSO.Alternation.`Both-Ports`

trait Chan[T] extends OutPort[T] with InPort[T] with Loggable {
  def withLogLevel(logLevel: Int): this.type =
  { level = logLevel
    this
  }

  /** Capture (an approximation to) the current state for debugger components */
  def currentState: String

  /** Close both ports of this channel */
  override def close(): Unit = {
    closeIn()
    closeOut()
  }

  /** The guard notation (from a channel) supports either outport or inport events  */
  override def &&(guard: => Boolean): GuardedPort[T] =
    GuardedPort[T](() => guard, `Both-Ports`(this))
}

/**
 *  The main channel factory. Channels are synchronised (`c!(x)` and `c?()` terminate
 *  at the same time) if `capacity==0`. Channels invent their own names if `name` is not
 *  provided.
 *
 *  {{{
 *    Chan[T](name, capacity)          -- yields a non-shared channel
 *    Chan.Shared(readers, writers)[T] -- yields a shared channel
 *  }}}
 *
 * A non-shared synchronised channel closes immediately its input
 * or output port is closed. When such a channel is shared, it closes
 * after `readers` `closeIn`s, or `writers` `closeOut`s.
 *
 * A buffered channel remains readable, even after a `closeOut`, as long
 * as there are unread items in its buffer.
 *
 * @see Chan.Shared
 */
object Chan extends serialNamer {
  val namePrefix: String = "Chan"
  def apply[T](capacity: Int): Chan[T] = capacity match {
    case 0 =>
      new SyncChan[T](nextName())
    case _ =>
      new BufferChan[T](nextName(), capacity)
  }

  def apply[T](name: String, capacity: Int): Chan[T] = capacity match {
    case 0 =>
      new SyncChan[T](name)
    case _ =>
      new BufferChan[T](name, capacity)
  }

  trait SharedChanGenerator {
    def readers: Int
    def writers: Int

    def apply[T](capacity: Int): Chan[T] = capacity match {
      case 0 =>
        new SharedSyncChan[T](nextName(), readers, writers)
      case _ =>
        new SharedBufferChan[T](nextName(), capacity, readers = readers, writers = writers)
    }

    def apply[T](name: String, capacity: Int): Chan[T] = capacity match {
      case 0 =>
        new SharedSyncChan[T](name)
      case _ =>
        new SharedBufferChan[T](name, capacity, readers = readers, writers = writers)
    }
  }

  /**
   * `Chan.Shared(readers, writers)(name, capacity)` yields a shared
   * channel of the given capacity, intended to be read by the
   * given number of readers and written by the given number
   * of writers. Closing of the shared channel in a given direction
   * takes place when the given number of `closeIn()`  (resp `closeOut()`)
   * have been invoked. If `readers==0` the channel never closes for input;
   * if `writers==0` the channel never closes for output.
   *
   * When its capacity is zero, the channel is a synced channel, and may not
   * engage with more than a single reader or writer in the same rendezvous.
   */
  object Shared extends SharedChanGenerator {
    def readers: Int = 0
    def writers: Int = 0

    def apply(readers: Int, writers: Int): SharedChanGenerator = {
      val withReaders=readers
      val withWriters=writers
      new SharedChanGenerator {
        def readers: Int = withReaders
        def writers: Int = withWriters
      }
    }
  }
}

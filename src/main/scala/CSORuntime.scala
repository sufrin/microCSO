package org.sufrin.microCSO

/**
 * Database of running processes and channels.
 * Intended to support
 */
object CSORuntime {
  def reset(): Unit = {
    vThreads.clear()
    vChannels.clear()
  }
  /** Mapping of (running) thread ids to `Thread`s */
  val vThreads =
    new scala.collection.concurrent.TrieMap[Long, Thread]

  val vChannels =
    new scala.collection.concurrent.TrieMap[Int, AnyRef]

  /** Evaluate `f` at each of the running threads  */
  def forEach(f: Thread=>Unit): Unit =
    vThreads.foreach{ case (_, thread) => f(thread)}

  /** Remove `thread` (and its locals) from the database (when terminating) */
  def remove(thread: Thread): Unit = {
    vThreads.remove(thread.threadId)
    thread.getState match {
      case Thread.State.TERMINATED => removeLocals(thread)
      case _ =>
    }
    vFormats.remove(thread.threadId)
  }

  /** Add `thread` to the database (when starting) */
  def add(thread: Thread): Unit = vThreads += (thread.threadId -> thread)

  /** Evaluate `f` at each of the as-yet-unclosed channels */
  def forEachChannel(f: Chan[Any] => Unit): Unit =
    vChannels.foreach{ case (_, chan) => f(chan.asInstanceOf[Chan[Any]])}

  /** Remove `chan` from the database (when it is completely closed) */
  def removeChannel(chan: Chan[_]): Unit = vChannels.remove(System.identityHashCode(chan))

  /** add `chan` to the database (as it is being constructed) */
  def addChannel(chan: Chan[_]): Unit = vChannels += (System.identityHashCode(chan) -> chan)

  //////////////////////// thread-local formatted states
  type LocalFormat= ()=> String

  val vFormats =
    new scala.collection.concurrent.TrieMap[Long, LocalFormat]

  def defFormat(format: => String): Unit = {
    val id  = Thread.currentThread().threadId
    vFormats += ( id -> { ()=> format } )
  }

  def forFormat(thread: Thread)(body: String => Unit): Unit = {
    for { thunk <- vFormats.get(thread.threadId) } {
      try { body(thunk()) }
      catch {
        case exn: Throwable => body(s"Format fails: $exn")
      }
    }
  }

  //////////////////////// thread-local variables: expensive

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

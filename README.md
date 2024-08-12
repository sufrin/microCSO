

# The microCSO Library for Scala

        Bernard Sufrin
        Emeritus: Department of Computer Science
        Oxford University

**microCSO** is a  new, and somewhat simplified, implementation
of my Communicating Scala Objects DSL (CSO) used from 2007 to teach
Oxford's  *Concurrent Programming* course. The main differences with **ThreadCSO**
are as follows:

   *  Processes run in lightweight threads
   by default when started, and this makes it possible to deploy tens or hundreds
   of thousands of them in an applicaiton (as does the most recent
   `ThreadCSO`). They are also (in `Java`/`Scala` terminology)
   `Runnable` so can also be run by any of the `java.util.Executor`
   implementations.

   *  The `PROC` type is now named `proc` as is the simple `proc` factory
   object. A simple `proc` `p` and a `Unit`-valued method `m`
   with the same body behave almost identically when applied.
   Thus, if defined by:\
   `val p: proc = proc { body }` or\
   `val p: proc = proc (name) { body }`\
   and\
   `def m: () => Unit = { body }` \
   then\
   `p()` and `p.run()` and `m()` have essentially the same
   behaviour; except that a thread in which `p` runs will
   (for the duration of its execution) change its name to `name` in
   order to make post-mortem/post-deadlock investigations
   straightforward.

  *  There are fewer varieties of channel, and their construction is
  done systematically in the single channel factory. Here `alpha`
  is declared as an unbuffered (synchronized) channel of `Int` and
  `omega` is a buffered channel of `String` with buffer capacity
  of 25. Neither of these may have more than one distinct
  reader or writer thread active "simultaneously". After its output side
  is closed, readers
  may continue to read from the channel until its buffer is empty.
````
        val alpha  = Chan[Int]("alpha", 0)
        val omega  = Chan[String]("omega", 25)
````
  * The buffered channel `shared` has the same capacity as `omega`
  but may have up to 2 reader threads and 4 writer threads active
  simultaneously. It expects
  4 `closeOut()` operations, before its output side is
  considered closed, and 2 `closeIn()` operations before its input side
  is considered closed. After its output side is closed, readers
  may continue to read from the channel until the buffer is empty.
````
        val shared = Chan.Shared(readers = 2, writers = 4)[String]("Shared", 25)
````
  *  Alternation constructs like `alt` and `serve` are implemented
  by fast and efficient polling -- invoked only when necessary. The
  inter-poll period default can be overridden for each alternation
  instance.

  *  The notation for alternation constructs has been dramatically
  simplified, so that runtime "compilation" is no longer necessary.
  In particular, the `after`, and `orelse` notations have been replaced.

  *  Control constructs `withPorts` and `WithPorts` that respect the network
  termination convention are provided for clarity and conciseness.
  

**See also** `Github.com/sufrin/ThreadCSO`


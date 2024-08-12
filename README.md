

# The microCSO Library for Scala

        Bernard Sufrin
        Emeritus: Department of Computer Science
        Oxford University

**microCSO** is a completely new, and somewhat simplified, implementation
of the Communicating Scala Objects DSL (CSO) that I designed and
built at Oxford University in 2007. The main differences with **ThreadCSO**
are as follows:

   1. When they are started, processes run in lightweight threads by default, and
   this makes it possible to deploy tens or hundreds of thousands
   of them in an applicaiton. They are still (in `Java`/`Scala`
   terminology) still `runnable`s so can also be run by any of the
   `java.util.Executor` implementations. 

   1. The `PROC` type is now named `proc`. The following
   operational equivalences hold between a process, `p`, and
   a procedure (method), `m`, defined by:

````
    val p: process   = proc { body }
    def m: ()=> Unit = { body }
````
   
   * `p.run()` is equivalent to `m()`

   * `p()`     is equivalent to `m()`
  

  3. There are fewer varieties of channel, and their construction is
  done systematically in a single channel factory.

  4. Alternation constructs like `alt` and `serve` are implemented
  by fast and efficient polling -- invoked only when necessary. The
  inter-poll period default can be overridden for each alternation
  instance.

  5. The notation for alternation constructs has been dramatically
  simplified, so that runtime "compilation" is no longer necessary.
  In particular, the `after`, and `orelse` notations have been replaced.

  6. Control constructs `withPorts` and `WithPorts` that respect the network
  termination convention have been provided for clarity and conciseness.
  

**See also** `Github.com/sufrin/ThreadCSO`


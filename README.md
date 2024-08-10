

# The microCSO Library for Scala

        Bernard Sufrin
        Emeritus: Department of Computer Science
        Oxford University

**microCSO** is a completely new, and somewhat simplified, implementation
of the Communicating Scala Objects DSL (CSO) that I designed and
built at Oxford University in 2007. The main differences with **ThreadCSO**
are as follows:

  1. processes lways run in lightweight threads

  2. the `PROC` type is now named `process`

  3. alternation constructs are implemented by fast and efficient polling

  4. There are fewer varieties of channel, and their construction is
  done systenmatically in a single channel factory.

**See also** `Github.com/sufrin/ThreadCSO`


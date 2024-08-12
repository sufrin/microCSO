package org.sufrin.microCSO

import Component._
import Time.{seconds, sleepms}
import proc._
import termination._
import org.sufrin.logging.{Default, FINEST, INFO, OFF}
import java.util.concurrent.atomic.AtomicLong


/**
 * Tests interaction of || with termination of simple processes
 */
object testSimpleParAndTermination extends testFramework {

  def test(): Unit = {
    Default.level = INFO
    val terminate = proc("Terminate") { show("'Output From Terminate'") }

    val failure = proc("Failure") { fail("within Failure") }

    val close = proc("Close") { throw new Closed("<no channel>") }

    val runStop = proc("runStop"){ stop }

    def sleep(ms: Long) = proc(s"sleep($ms)"){ Thread.sleep(ms); show(s"slept $ms")}

   // run(sleep(5000))
    //apply(terminate)
    apply(failure)
    apply(close)
    apply(failure || terminate)
    apply(runStop || terminate)
    apply(runStop || failure)
    apply(failure || terminate)
    apply(close || terminate)
    apply(terminate || close)

    def stopping(n: Int) = proc(s"stopping($n)") {
      repeatFor ( 1 to 15 ){
        case i if i==n => stop
        case i: Int    => print(s"(s:$i)")
      }
    }

    def failing(n: Int) = proc(s"failing($n)") {
      repeatFor ( 1 to 15 ){
        case i if i==n => assert(false, s"reached $n")
        case i: Int    => print(s"(f:$i)")
      }
    }

    run(failing(10))
    run(stopping(10))
    frun(stopping(10)||failing(6))

  }
}

object testBufferClose extends testFramework {
  def test(): Unit = {
    Component.level=FINEST

    for { size<-List(0, 1, 5, 10, 20)} {
      println(s"source(1..15) -> Chan($size) -> sink")
      val as = Chan[Int]("as", size).withLogLevel(FINEST)
      run(
        source((1 to 15).toList)(as) || sink(as) { s => show(s.toString) }
      )
    }
  }
}
/**
 * Tests termination interacting with || and with closed channels
 */
object testParAndTermination extends testFramework {

    def test() = {
      Default.level = INFO

      run((proc("first") { show("first") } || proc("second") { show("second") }))

      val names = "first second third fourth".split(' ').toSeq
      val procs = names map { name => proc(name) { show(name)}}
      run(||(procs))

      run(||(for {i <- 0 until 10} yield proc(s"$i") {
        show(s"$i")
      }))

      { println("UnShared Buffered")
        val c = Chan[String]("c", 4)
        val s = source("the rain in spain falls mainly in the plain".split(' '))(c)
        val t = sink(c) { s => show(s"'$s'") }
        run(s || t)
      }

      { println("UnShared Buffered: sink stops early")
        val c = Chan[String]("c", 1)
        val s = source("the rain in spain falls mainly stop in the plain".split(' '))(c)
        val t = sink(c) {
          case "stop" => stop
          case s => show(s"'$s' ")
        }
        run(s || t)
      }

      { println("UnShared Synced: sink stops early")
        val c = Chan[String]("c", 0)
        val s = source("the rain in spain falls mainly stop in the plain".split(' '))(c)
        val t = sink(c) {
          case "stop" => stop
          case s => show(s"'$s' ")
        }
        run(s || t)
      }

      { println("Shared Synced: sink stops early")
        val c = Chan.Shared(1, 1)[String]("c", 0)
        val s = source("the rain in spain falls mainly stop in the plain".split(' '))(c)
        val t = sink(c) {
          case "stop" => stop
          case s => show(s"'$s' ")
        }
        run(s || t)
      }

      { println("Timeout test")
        import Time._
        run(proc("Sleeper"){
          println("I am about to sleep for 5.1s")
          sleep(seconds(5.1))
        })
      }
    }
  }

/**
 *  A test of the termination protocols that
 *  exposes late reactions to peers closing. Or,
 *  to be more precise, /exposed/. The `zip`
 *  loop restarts its reader at each
 *  iteration; and the reads need to be terminated
 *  if the output ports close as well as if they succeed.
 */
object testZip extends testFramework {
  def test(): Unit = {

    for {zbuf<-List(0, 4); bufSize <- List(0, 1, 30)} {
      if (logging) Default.level = INFO
      if (logging) Default.info(s"Zip trial $bufSize (zipped: $zbuf)")
      val as = Chan[Int]("as", bufSize)
      val bs = Chan[Int]("bs", bufSize)
      val zipped = Chan[(Int, Int)]("zipped", zbuf)
      run(   source((1 to 25).toList)(as)
          || source((1 to 35).toList)(bs)
          || zip(as, bs)(zipped)
          || sink(zipped) { p => show(s"$p") }
      )
    }


    for {zbuf<-List(0, 10); bufSize <- List(30, 2, 0)} {
      if (logging) Default.level = INFO
      if (logging) Default.info(s"Zip trial $bufSize taking 15 (zipped: $zbuf)")
      val as = Chan[Int]("as", bufSize)
      val bs = Chan[Int]("bs", bufSize)
      val zipped = Chan[(Int, Int)]("zipped", zbuf)
      run( source((1 to 25).toList)(as)
        || source((1 to 35).toList)(bs)
        || zip(as, bs)(zipped)
        || proc("take 15") {
            for {_ <- 0 until 15} show(s"${zipped ? ()}")
            zipped.closeIn()
           }
      )
    }

    for {bufSize <- List(30, 2, 0)} {
      if (logging) Default.level = INFO
      if (logging) Default.info(s"Zip trial $bufSize taking triples up to k")
      val as = Chan[Int]("as", bufSize)
      val bs = Chan[Int]("bs", bufSize)
      val cs = Chan[Char]("cs", bufSize)
      val zipped = Chan[(Int, Int, Char)]("zipped", 100)
      run( source((1 to 25).toList)(as)
        || source((1 to 35).toList)(bs)
        || source("abcdefghijk")(cs)
        || zip(as, bs, cs)(zipped)
        || proc("take 15") {
               for {_ <- 0 until 150} show(s"${zipped ? ()}")
               zipped.closeIn()
           }
      )
    }

  }
}

/**
 * TODO: check idempotence of close in buffers.
 *
 * A problem emerged with buffers/channels being closed too early -- possibly
 * because of the network termination protocol interacting with non-idempotence
 * of (buffered) channel closing.
 *
 */
object testZips extends testFramework {
  def test(): Unit = {
    println("Results < 26 suggest an channel closing early problem somewhere")
    testRun(0, 0, 0, 0)()
    testRun(0, 0, 0, 10)()
    testRun(0, 0, 0, 100)()
    testRun(1, 1, 1, 0)()  // problematic count 25
    testRun(1, 1, 0, 0)()  // problematic count 25
    testRun(1, 1, 1, 10)() // problematic count 25
    testRun(2, 0, 0, 100)() // problematic count 24
    testRun(2, 0, 0, 10)() // problematic count 24
    testRun(2, 2, 2, 10)() // problematic count 24
    testRun(0, 2, 2, 10)() // problematic count 24
    testRun(0, 0, 2, 10)() // problematic count 24
    testRun(0, 0, 5, 0)()  // problematic count 24
    testRun(0, 0, 7, 0)()  // problematic count 19
    testRun(10, 10, 10, 0)()  // problematic count 16
    testRun(10, 10, 20, 0)()  // problematic count 6
    testRun(9, 9, 20, 0)()  // problematic count 6
    testRun(9, 9, 20, 20)()  // problematic count 6
    testRun(9, 9, 0, 20)()  // problematic count 17
  }
  def testRun(a:Int, z: Int, i: Int, o: Int)(chanLevel: Int=OFF, compLevel: Int=OFF): Unit = {
    //println(s"testZips($a,$z,$i,$o)")
    val level = chanLevel
    Component.level = compLevel
    val a2z  = Chan[Char]("a2z", a).withLogLevel(level)
    val z2a  = Chan[Char]("z2a", z).withLogLevel(level) // comples; but not if capacity>4
    val ints = Chan[Int]("ints", i).withLogLevel(level)
    val out  = Chan[(Int, Char, Char)]("out", o)
              .withLogLevel(level)
    val az = "abcdefghijklmnopqrstuvwxyz"
    val za = az.reverse
    var count = 0
    (source(az)(a2z)
    ||  source(za)(z2a)
    ||  proc ("ints") {
        withPorts(ints) {
          var n: Int = 0
          repeatedly { ints!n; n+=1; if (n==26) ints.closeOut() }
        }
      }
    || zip(ints, a2z, z2a)(out)
    || sink(out) { triple =>/*print(s"$triple")*/; count+=1 }
    )()
    println(s"testZips($a,$z,$i,$o) -> $count")  }
}

object testClosing extends testFramework {
  var runs = 0
  def test(): Unit = {
    println("====== Downstream closing (0..100 -> alpha -> mid -> omega | 100")
    testRun(0, 0, 0, 100)()
    testRun(0, 0, 1, 100)()
    testRun(0, 0, 50, 100)()
    testRun(0, 0, 60, 100)()
    testRun(0, 0, 90, 100)()
    testRun(0, 5, 90, 100)()
    testRun(0, 20, 90, 100)()
    println(s"$runs tests")
    println("====== Upstream closing (0..100 -> alpha -> mid -> omega -> | limit")
    testRun(0, 0, 0, 50)()
    testRun(0, 20, 90, 20)()
    testRun(0, 20, 90, 25)()
    testRun(1, 20, 90, 50)()
    testRun(1, 20, 90, 20)()
    testRun(1, 20, 90, 25)()

    testRun(2, 20, 90, 50)()
    testRun(2, 20, 90, 20)()
    testRun(2, 20, 90, 25)()

    testRun(2, 0, 90, 50)()
    testRun(2, 0, 90, 20)()
    testRun(2, 0, 90, 25)()

    // more arbitrary buffering
    for ( a<-0 to 5; m<-0 to 7; z<-0 to 3)
         testRun(a, 2*m, 7*z, 40)()

    println(s"$runs tests")
  }

  def testRun(a:Int, m: Int, z: Int, o: Int)(chanLevel: Int=OFF, compLevel: Int=OFF): Unit = {
    val level       = chanLevel
    Component.level = compLevel
    val alpha  = Chan[Int]("alpha", a).withLogLevel(level)
    val omega  = Chan[Int]("omega", z).withLogLevel(level)
    val mid    = Chan[Int]("mid", m).withLogLevel(level)
    runs += 1

    var count, last = new AtomicLong(0)
    val handle: ForkHandle =
      (    source((1 to 100))(alpha)
        || copy(alpha, mid)
        || copy(mid, omega)
        || sink(omega) { n => count.incrementAndGet(); last.set(n); if (n >= o) omega.closeIn() }
      ).fork()
    val (terminated, status) = handle.terminationStatus(seconds(3))
    if (!terminated) printStatus(status)
    if (count.get()!=o) println(s"testRun(1..100 -> Chan($a) -> Chan($m) -> Chan($z) | $o) => ${count} ($last)")  }
}


/**
 *  Tests various aspects of sharing (buffered/synchronous) channels.
 */
object testSharing extends testFramework {
  Default.level = INFO

  def test(): Unit = {
    import Time._
    def useChan(share: Chan[String]): proc =
      ||(proc("l") {
        var n = 0
        repeat(n < 10) {
          share ! s"l$n";
          n += 1
        }
        show(s"l STOP")
        share.closeOut()
      }
        , proc("r") {
          var n = 0
          repeat(n < 10) {
            share ! s"r$n";
            n += 1
          }
          show(s"r STOP")
          share.closeOut()
        }
        , sink(share) { n => show(s"1->$n") }
        , sink(share) { n => show(s"2->$n") }
      )

    def shareTest(bufSize: Int): Unit = {
      org.sufrin.microCSO.CSORuntime.reset()
      val shared = Chan.Shared(readers = 2, writers = 2)[String]("Shared", bufSize)
      println(s"ShareTest $bufSize")
      run(useChan(shared))
    }

    println(s"==================== NonSync Overtaking Test (Assertion Error, then timeout expected)")
    val ch1: Chan[String] = Chan[String]("ch1", 0)
    run(useChan(ch1))

    println(s"==================== Finite sharing test 1")
    val ch2: Chan[String] = Chan.Shared(readers=2, writers=2)[String]("ch2", 0)
    run(proc("w1"){ ch2!"from w1"} ||
        proc("w2"){ ch2!"from w2"} ||
        proc("ra"){ ch2?{ s=>show(s"ra<-$s")} } ||
        proc("rb"){ ch2?{ s=>show(s"rb<-$s")} })

    println(s"==================== Finite sharing test 2")
    val ch3: Chan[String] = Chan.Shared(readers=2, writers=2)[String]("ch3", 0)
    run(||(proc("w1"){ ch3!"from w1"},
           proc("rb"){ Time.sleepms(1000); ch3?{ s=>show(s"rb<-$s")} },
           proc("w2"){ ch3.writeBefore(2000*milliSec)("from w2")},
           proc("ra"){ ch3?{ s=>show(s"ra<-$s")} },
       ))

    println("===================== Unshared/unbuffered linear termination test")
    val unsharedunbuffered: Chan[Int] = Chan[Int]("Unshared Unbuffered", 0)
    frun((source(0 until 16)(unsharedunbuffered)
       || sink(unsharedunbuffered) { n => show(s" $n") }))

    println("===================== Unshared/buffered linear termination test")
    val unshared: Chan[Int] = Chan[Int]("Unshared Buffered", 2)
    frun((source(0 until 16)(unshared) || sink(unshared) { n => show(s" $n") }))

    println("===================== Convergent unshared/buffered termination test [EXPECT AN OVERTAKING ERROR]")
    val conv: Chan[Int] = Chan[Int]("Converge", 16)
    frun((source(1 to 20)(conv)  ||
          source(21 to 50)(conv) ||
          sink(conv) { n => show(s" $n"); if (n==20) Time.sleepms(20) }
        ))

    println("===================== Convergent shared/buffered termination test")

    {
      val conv: Chan[Int] = Chan.Shared(writers=2, readers=1)[Int]("Converge", 16)
      frun((source(1 to 20)(conv) ||
        source(21 to 50)(conv) ||
        sink(conv) { n => show(s" $n"); if (n == 20) Time.sleepms(20) }
        ))
    }

    println("===================== Shared unbuffered linear termination test")
    val shared: Chan[Int] = Chan.Shared(readers = 1, writers = 1)[Int]("Shared", 0)
    frun((source(0 until 16)(shared) || sink(shared) { n => show(s" $n") }))

    println("===================== Multiway shared tests")
    shareTest(0)
    shareTest(1)
    shareTest(10)

  }
}

/**
 * This exercises the dynamic test for overtaking in a synchronous channel.
 */
object testOvertaking extends testFramework {
  import Time._
  override val deadline = seconds(0.95)


  def test(): Unit = {
    Default.level = INFO
    frun ( proc("deliberate assertion failure") { assert(false, "this is a deliberate assertion failure ")} )

    val c  = Chan[String]("c", 0)
    run ( proc ("caption"){ println("This should report an assertion error, then deadlock (one of the branches fails to terminate)") } ||
           proc ("A"){ c!"A"; c.closeOut(); println("!A done"); System.out.flush() } ||
           proc ("B"){ c!"B"; c.closeOut(); println("!B done"); System.out.flush() } )



    val d  = Chan[String]("d", 0)
    run ( || (
      proc ("caption") { println("This should report an assertion error, then deadlock (one of the branches fails to terminate)") } ,
      proc ("D2") { d?{ s => show(s); d.closeIn() } } ,
      proc ("D1") { d?{ s => show(s); d.closeIn() } } )
    )

    val e  = Chan[String]("e", 0)
    run ( (
      proc ("caption") { println("This should report an assertion error, then deadlock (one of the branches fails to terminate)") } ||
      proc ("E2") { e?{ s => show(s); e.closeIn() } } ||
      proc ("E1") { e?{ s => show(s); e.closeIn() } } )
    )
  }
}

/**
 * This is a simple test for readBefore/writeBefore.
 *
 * Each trial uses a new channel and it single-shot reads and writes on the channel.
 * The reader in `readDeadline`  waits for a read to complete in `readR`, and
 * the writer delays the write for `waitW`.
 *
 * Readers/writers in  `writeDeadline`  delays the read and awaits the write.
 *
 * All readers(writers) close their input(output) port when done: this
 * averts non-termination of their peer, thus timeout of the test
 * as a whole.
 */
object testDeadlines extends testFramework {

  def test(): Unit = {
     import Time._
     val Sec=seconds(1)

     def readDeadline(chan: Chan[String], writeDelay: Double, readDead: Double): proc = {
       val delay = if (writeDelay>readDead) "(reader times out)" else ""
       ||(
         proc ("caption") { println(f"readDeadline($chan%s, writeDelay=${writeDelay}%f, readDead=$readDead)" + delay) },
         proc ("reader")  { val r = chan.readBefore(seconds(readDead)); println(s"reader=$r"); chan.closeIn() },
         proc ("writer")  { sleep(seconds(writeDelay)); chan!"WRITTEN"; println("written"); chan.closeOut() }
       )
     }

     def writeDeadline(chan: Chan[String], writeDead: Double, readDelay: Double): proc = {
      val delay = if (writeDead<readDelay) "(writer times out)" else ""
      ||(
        proc ("caption") { println(s"writeDeadline($chan, writeDead=$writeDead, readDelay=$readDelay)" + delay) },
        proc ("reader")  { sleep(seconds(readDelay)); val r = chan?(); println(s"reader?$r"); chan.closeIn() },
        proc ("writer")  { if (chan.writeBefore(seconds(writeDead))("WRITTEN")) println("written") else println("unwritten"); chan.closeOut() }
      )
    }

    //                        write delay     read deadline
    run(readDeadline(Chan(0), (0.01),         (0.5)))
    run(readDeadline(Chan(0), (0.5),          (1.0)))
    run(readDeadline(Chan(0), (1.0),          (1.5)))
    run(readDeadline(Chan(0), (1.499999),     (1.5)))
    run(readDeadline(Chan(0), (0.4999999999), (0.5)))
    run(readDeadline(Chan(1), (1.5),          (1.0)))
    run(readDeadline(Chan(1), (4.0),          (1.5)))
    run(readDeadline(Chan(1), (1.499999),     (1.5)))
    run(readDeadline(Chan(1), (0.4999999999), (0.5)))

    //                         write deadline read delay
    run(writeDeadline(Chan(0), (0.1),         (0.5)))
    run(writeDeadline(Chan(0), (0.5),         (1.0)))
    run(writeDeadline(Chan(0), (1.0),         (0.5)))
    run(writeDeadline(Chan(1), (0.01),        (0.5)))
    run(writeDeadline(Chan(1), (0.5),         (1.0)))
    run(writeDeadline(Chan(1), (1.0),         (0.5)))
    run(writeDeadline(Chan(1), (1.0),         (0.99999999)))

  }
}

/**
 *   Tests the asynchronous constructs (offer/poll) designed to support
 *   Alt.
 */
object testPolling extends testFramework {
  import Time._
  def test(): Unit = {
    def offerer(chan: Chan[Int], delay: Double, to: Int): proc = proc (f"offerer($delay%f, $to%d)") {
      val every = seconds(delay)
      for { i<-0 until to } {
        val r = chan.offer(()=>i)
        show(s"[$i $r]")
        sleep(every)
      }
      chan.closeOut()
    }

    def writer(chan: Chan[Int], delay: Double, to: Int): proc = proc (f"writer($delay%f, $to%d)") {
      val every = seconds(delay)
      for { i<-0 until to } {
        sleep(every)
        chan!i
        show(s"[$i]")
      }
      chan.closeOut()
    }

    def reader(chan: Chan[Int], delay: Double, to: Int): proc = proc (f"$chan%s: reader($delay%f, $to%d)") {
      var d = seconds(delay)
      for { i<-0 until to } {
        val r = chan?()
        show(s"->$r")
        sleep(d)
      }
      chan.closeIn()
    }

    def poller(chan: Chan[Int], delay: Double, to: Int): proc = proc (f"$chan%s: poller($delay%f, $to%d)") {
      var every = seconds(delay)
      for { i<-0 until to } {
        sleep(every)
        val r = chan.poll()
        show(s"->$r")
      }
      chan.closeIn()
    }

    def offererReader(chan: Chan[Int])(readDelay: Double, countReads: Int)(writeDelay: Double, countWrites: Int): Unit = {
      run(reader(chan, readDelay, countReads) || offerer(chan, writeDelay, countWrites))
    }

    def writerPoller(chan: Chan[Int])(readDelay: Double, countReads: Int)(writeDelay: Double, countWrites: Int): Unit = {
      run(poller(chan, readDelay, countReads) || writer(chan, writeDelay, countWrites))
    }

    println(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>> Polls/buffered")

    writerPoller(Chan[Int]("ch1(10)", 10))(0.0001, 20)(0.0001, 20)
    writerPoller(Chan[Int]("ch1(10)", 10))(0.0001, 20)(0.0002, 20)
    writerPoller(Chan[Int]("ch1(10)", 10))(0.0001, 10)(0.0004, 30)
    writerPoller(Chan[Int]("ch1(20)", 20))(0.0001, 20)(0.0006, 20)

    println(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>> Polls/unbuffered")

    writerPoller(Chan[Int]("ch1", 0))(0.0001, 20)(0.0001, 20)
    writerPoller(Chan[Int]("ch1", 0))(0.0001, 20)(0.0002, 20)
    writerPoller(Chan[Int]("ch1", 0))(0.0001, 10)(0.0004, 30)
    writerPoller(Chan[Int]("ch1", 0))(0.0001, 20)(0.0006, 20)

    println(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>> Offers/buffered")
    offererReader(Chan[Int]("ch1(10)", 10))(0.0001, 20)(0.0001, 20)
    offererReader(Chan[Int]("ch1(10)", 10))(0.002, 20)(0.0001, 20)
    offererReader(Chan[Int]("ch1(10)", 10))(0.004, 10)(0.0001, 30)
    offererReader(Chan[Int]("ch1(20)", 20))(0.002, 20)(0.0001, 20)


    println(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>> Offers/unbuffered")
    offererReader(Chan[Int]("ch1", 0))(0.00001, 20)(0.0001, 20)
    offererReader(Chan[Int]("ch1", 0))(0.002, 20)(0.0001, 20)
    offererReader(Chan[Int]("ch1", 0))(0.004, 10)(0.0001, 30)
    offererReader(Chan[Int]("ch1", 0))(0.002, 20)(0.0001, 20)

  }

}

/**
 *  Tests `Serve` functionality using the Component.Merge` component.
 *  Among other things this test demonstrated the importance of getting
 *  readers and writers counts correct on (nominally) shared channels.
 */
object testMerge extends testFramework {
  val N: Int = 2
  def labelled(n: Int): Iterable[(Int, Int)] = for {i <- 0 until 10} yield (n, i)

  def test(): Unit = {
    Component.level = OFF
    for {bufSize <- List(0, 1, 5)} {

      val chans: Seq[Chan[(Int, Int)]] = (0 until N).toList.map { case n: Int => Chan[(Int, Int)](s"chan$n", 0) }

      val producers =
        ||(for {chan <- 0 until N} yield source[(Int, Int)](labelled(chan))(chans(chan)))

      // No need for the sharing; but when I got the writers count wrong the failure to close
      // caused sink(merged) below to deadlock at the top of its read loop.
      val merged = Chan.Shared(readers = 1, writers = 1)[(Int, Int)]("merged", bufSize)

      var count = 0
      println(s"Merge trial $N producers (Merge channel buffer size = $bufSize)")
      frun( producers
        || Merge(chans)(merged)
        || sink(merged) { case (chan, n) => show(f"[$chan%02d->$n%02d@$count%02d]"); count += 1 }
      )
    }

    for { bufSize <- List(0, 1, 5) } {
      val chans: Seq[Chan[(Int, Int)]] = (0 until N).toList.map { case n: Int => Chan[(Int, Int)](s"chan$n", 0) }

      val producers =
        ||(for {chan <- 0 until N} yield source[(Int, Int)](labelled(chan))(chans(chan)))

      val merged = Chan[(Int, Int)]("merged", bufSize)

      var count = 0
      println(s"Merge trial $N producers (output termination) (Merge channel size = $bufSize)")
      println(s"This also demonstrates selective logging of channel events")
      for { chan <- chans } chan.level = FINEST
      Component.level=FINEST
      merged.level=FINEST
      run(producers             ||
          Merge(chans)(merged)  ||
          sink(merged) {
            case (chan, n) =>
              show(f"[$chan%02d->$n%02d@$count%02d]")
              if (count==27) stop
              count += 1
          }
      )
    }

  }
}

object testEvents extends testFramework {
  import Alternation._
  def test(): Unit = {
    Default.level=INFO
    def testReadWrite(capacity: Int): Unit =
    { val level = OFF
      println(s"testReadWrite($capacity)")
      val l = Chan[String]("l", capacity).withLogLevel(level)
      val c = Chan[(String,String)]("c", capacity).withLogLevel(level)
      val d = Chan[(String,String)]("d", capacity).withLogLevel(level)
      val r = Chan[String]("r", capacity).withLogLevel(level)
      var m, n = 0
      var going = 0

      val source = proc("Source") {
          serve(
            (l && (n < 20)) =!=> {
              n += 1; s"$n"
            }
            ,
            (r && (m < 20)) =!=> {
              m += 1; sleepms(15); s"$m"
            }
            ,
            (c && going < 21) =?=> {
              case s =>
                d ! s
                going += 1
                if (going==20) {
                  l.closeOut()
                  r.closeOut()
                }
            }
            // without guarding c the serve loop livelocks
          )
          c.info(s"$c")
          Default.info("Source finished")

        l.closeIn(); r.closeIn(); c.closeIn(); d.closeOut()
      }
      frun(source || zip(l, r)(c) || sink(d){ s => println(s) })
    }

    testReadWrite(0)
    testReadWrite(4)

    def testCopy(capacity: Int): Unit =
    { println(s"testCopy($capacity)")
      val l = Chan[String]("l", capacity).withLogLevel(FINEST)
      val c = Chan[(String,String)]("c", capacity).withLogLevel(FINEST)
      val d = Chan[(String,String)]("d", capacity).withLogLevel(FINEST)
      val r = Chan[String]("r", capacity).withLogLevel(FINEST)
      var m, n = 0

      def copy[T](i: InPort[T], o: OutPort[T]): proc = proc(s"xfer($i,$o)"){
        withPorts(i, o) {
          var buf: Option[T] = None
          serve(
            (i && buf.isEmpty) =?=> { t => buf = Some(t) }
            ,
            (o && buf.isDefined) =!=> { val t = buf.get; buf=None; t }
          )
          Default.info("Copy finished")
        }
        println(s"$i\n$o\n")
      }

      val source = proc("Source") {

          serve(
            (l && n < 20) =!=> {
              n += 1; s"$n"
            }
            ,
            (r && m < 20) =!=> {
              m += 1; sleepms(15); s"$m"
            }
          )
          Default.info("Source finished")
          l.closeIn(); r.closeOut()
          println(s"$l\n$r\n")
      }
      run(source || zip(l, r)(c) || copy(c, d) || sink(d){ s => println(s) })
    }

    //+ testCopy(0)
    //+ testCopy(1)

    def testReadEvents(): Unit =
    { val l = Chan[String]("l", 0)
      val c = Chan[String]("c", 0)
      val r = Chan[String]("r", 0)

      val lev = l =?=> { s: String => show(s"[l:$s]") }
      val cev = c =?=> { s: String => show(s"[c:$s]") }
      val rev = r =?=> { s: String => show(s"[r:$s]") }

      val server: proc = proc("serve") {
        serve(lev, rev, cev)
        Default.finer(s"serve() terminated")
      }

      run( server
        || source("my dog has fleas".split(' '))(c)
        || source((1 to 20).map(_.toString))(l)
        || source((100 to 120).map(_.toString))(r)
      )
    }

    //+ testReadEvents()

    def testWriteEvents(): Unit =
    { println(s"testWriteEvents()")
      val l = Chan[String]("l", 0)
      val c = Chan[(String,String)]("c", 0)
      val r = Chan[String]("r", 0)
      var m, n = 0

      val lrOutput = proc("lrOutput") {
        withPorts(l, r) {
          serve(
            (l && n < 20) =!=> { n += 1; s"$n" }
            ,
            (r && m < 20) =!=> { m += 1; sleepms(35); s"$m" }
          )
        }
      }
      run(lrOutput || zip(l, r)(c) || sink(c){ s => show(s"$s") })
    }

    //+ testWriteEvents()

    def testAltBefore(): Unit = {
      println(s"testAltBefore() [Nothing ppen twice; then an alternation failure]")
      val l = Chan[String]("l", 0)
      val c = Chan[String]("c", 0)
      val r = Chan[String]("r", 0)
      val stops = proc ("stops") {
        val started = Time.milliTime
        println("Started")

        altBefore(seconds(0.5), orElse={ println(s"Nothing open @ ${Time.milliTime-started}")})(
          l =?=> { x => show(x) },
          r =?=> { x => show(x) },
          c =?=> { x => show(x) }
        )

        altBefore(orElse={ println(s"Nothing open @ ${Time.milliTime-started}")})(
          l =?=> { x => show(x) },
          r =?=> { x => show(x) },
          c =?=> { x => show(x) }
        )

        alt(
          l =?=> { x => show(x) },
          r =?=> { x => show(x) },
          c =?=> { x => show(x) }
        )
      }

      val closes = proc("closes") {
        withPorts (l, r, c) {
          l!"HELLO"
          Time.sleep(seconds(1))
          println("Closing l")
          l.closeIn()
          Time.sleep(seconds(1))
          println("Closing r")
          r.closeIn()
          Time.sleep(seconds(1))
          println("Closing c")
          c.closeIn()
        }
      }
      run(stops || closes)
    }

    //+ testAltBefore()

    def testServe(): Unit = {
      println(s"testServe() []")
      import Time._
      val c = Chan[Int]("c", 0)
      val listen = proc("listen") {
        val start = Time.milliTime
        def now= Time.milliTime-start

        altBefore(50*milliSec, afterDeadline={ show(s"==@$now")}, orElse = { println("finished")})(
          c =?=> { x =>  show(s"[$x@$now]") }
        )

        serveBefore(50*milliSec, afterDeadline={ show("*")}, orElse = { println("finished")})(
          c =?=> { x =>  show(s"{$x@$now}") }
        )
      }

      val speak= proc("speak") {
        sleep(180*milliSec)         // any shorter delay here meets the altBefore deadline.
        for { i<-0 until 3} {
          c!i
          sleep(100*milliSec)
        }
        sleep(1000*milliSec)
        c.closeOut()
      }
      run(speak||listen)
    }

    // + testServe()
  }
}




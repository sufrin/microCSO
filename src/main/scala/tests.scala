package org.sufrin.microCSO

import org.sufrin.logging.{Default, FINEST, INFO, OFF}
import org.sufrin.microCSO.Component._
import org.sufrin.microCSO.proc._
import org.sufrin.microCSO.termination._
import org.sufrin.microCSO.Time.{seconds, sleepms}


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

/**
 * Tests termination interacting with || and with closed channels
 */
object testParAndTermination extends testFramework {

    def test() = {
      Default.level = INFO

      run((proc("first") {
        show("first")
      } || proc("second") {
        show("second")
      }))

      val names = "first second third fourth".split(' ').toSeq
      val procs = names map { name => proc(name) { show(name)}}

      run(||(procs))

      run(||(for {i <- 0 until 10} yield proc(s"$i") {
        show(s"$i")
      }))

      {
        val as = Chan[Int]("as", 10)
        run(
          source(as, (0 until 15).toList) || sink(as) { s => show(s.toString) }
        )
      }


      {
        val as = Chan[Int]("as", 1)
        run(
          source(as, (0 until 15).toList) || sink(as) { s => show(s.toString) }
        )
      }

      { println("UnShared Buffered")
        val c = Chan[String]("c", 4)
        val s = source(c, "the rain in spain falls mainly in the plain".split(' '))
        val t = sink(c) { s => show(s"'$s'") }
        run(s || t)
      }

      { println("UnShared Buffered: sink stops early")
        val c = Chan[String]("c", 1)
        val s = source(c, "the rain in spain falls mainly stop in the plain".split(' '))
        val t = sink(c) {
          case "stop" => stop
          case s => show(s"'$s' ")
        }
        run(s || t)
      }

      { println("UnShared Synced: sink stops early")
        val c = Chan[String]("c", 0)
        val s = source(c, "the rain in spain falls mainly stop in the plain".split(' '))
        val t = sink(c) {
          case "stop" => stop
          case s => show(s"'$s' ")
        }
        run(s || t)
      }

      { println("Shared Synced: sink stops early")
        val c = Chan.Shared(1, 1)[String]("c", 0)
        val s = source(c, "the rain in spain falls mainly stop in the plain".split(' '))
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
      run(
        source(as, (1 to 25).toList)
          || source(bs, (1 to 35).toList)
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
      run( source(as, (1 to 25).toList)
        || source(bs, (1 to 35).toList)
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
      run( source(as, (1 to 25).toList)
        || source(bs, (1 to 35).toList)
        || source(cs, "abcdefghijk")
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
 *  Tests various aspects of sharing (buffered/synchronous) channels.
 */
object testSharing extends testFramework {
  Default.level = INFO

  def test(): Unit = {
    import Time._
    def useChan(share: Chan[String]): process =
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
        proc("ra"){ ch2?{s=>show(s"ra<-$s")} } ||
        proc("rb"){ ch2?{s=>show(s"rb<-$s")} })

    println(s"==================== Finite sharing test 2")
    val ch3: Chan[String] = Chan.Shared(readers=2, writers=2)[String]("ch3", 0)
    run(||(proc("w1"){ ch3!"from w1"},
           proc("rb"){ Time.sleepms(1000); ch3?{s=>show(s"rb<-$s")} },
           proc("w2"){ ch3.writeBefore(2000*milliSec)("from w2")},
           proc("ra"){ ch3?{s=>show(s"ra<-$s")} },
       ))

    println("===================== Unshared/unbuffered linear termination test")
    val unsharedunbuffered: Chan[Int] = Chan[Int]("Unshared Unbuffered", 0)
    frun((source(unsharedunbuffered, 0 until 16) || sink(unsharedunbuffered) { n => show(s" $n") }))

    println("===================== Unshared/buffered linear termination test")
    val unshared: Chan[Int] = Chan[Int]("Unshared Buffered", 2)
    frun((source(unshared, 0 until 16) || sink(unshared) { n => show(s" $n") }))

    println("===================== Convergent unshared/buffered termination test [EXPECT AN OVERTAKING ERROR]")
    val conv: Chan[Int] = Chan[Int]("Converge", 16)
    frun((source(conv, 1 to 20)  ||
          source(conv,  21 to 50) ||
          sink(conv) { n => show(s" $n"); if (n==20) Time.sleepms(20) }
        ))

    println("===================== Convergent shared/buffered termination test")

    {
      val conv: Chan[Int] = Chan.Shared(writers=2, readers=1)[Int]("Converge", 16)
      frun((source(conv,  1 to 20) ||
        source(conv, 21 to 50) ||
        sink(conv) { n => show(s" $n"); if (n == 20) Time.sleepms(20) }
        ))
    }

    println("===================== Shared unbuffered linear termination test")
    val shared: Chan[Int] = Chan.Shared(readers = 1, writers = 1)[Int]("Shared", 0)
    frun((source(shared, 0 until 16) || sink(shared) { n => show(s" $n") }))

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

     def readDeadline(chan: Chan[String], writeDelay: Double, readDead: Double): process = {
       val delay = if (writeDelay>readDead) "(reader times out)" else ""
       ||(
         proc ("caption") { println(f"readDeadline($chan%s, writeDelay=${writeDelay}%f, readDead=$readDead)" + delay) },
         proc ("reader")  { val r = chan.readBefore(seconds(readDead)); println(s"reader=$r"); chan.closeIn() },
         proc ("writer")  { sleep(seconds(writeDelay)); chan!"WRITTEN"; println("written"); chan.closeOut() }
       )
     }

     def writeDeadline(chan: Chan[String], writeDead: Double, readDelay: Double): process = {
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
    def offerer(chan: Chan[Int], delay: Double, to: Int): process = proc (f"offerer($delay%f, $to%d)") {
      val every = seconds(delay)
      for { i<-0 until to } {
        val r = chan.offer(()=>i)
        show(s"[$i $r]")
        sleep(every)
      }
      chan.closeOut()
    }

    def writer(chan: Chan[Int], delay: Double, to: Int): process = proc (f"writer($delay%f, $to%d)") {
      val every = seconds(delay)
      for { i<-0 until to } {
        sleep(every)
        chan!i
        show(s"[$i]")
      }
      chan.closeOut()
    }

    def reader(chan: Chan[Int], delay: Double, to: Int): process = proc (f"$chan%s: reader($delay%f, $to%d)") {
      var d = seconds(delay)
      for { i<-0 until to } {
        val r = chan?()
        show(s"->$r")
        sleep(d)
      }
      chan.closeIn()
    }

    def poller(chan: Chan[Int], delay: Double, to: Int): process = proc (f"$chan%s: poller($delay%f, $to%d)") {
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
 *  Tests `Serve` functionality using the Component.merge` component.
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
        ||(for {chan <- 0 until N} yield source[(Int, Int)](chans(chan), labelled(chan)))

      // No need for the sharing; but when I got the writers count wrong the failure to close
      // caused sink(merged) below to deadlock at the top of its read loop.
      val merged = Chan.Shared(readers = 1, writers = 1)[(Int, Int)]("merged", bufSize)

      var count = 0
      println(s"Merge trial $N producers (merge channel buffer size = $bufSize)")
      frun( producers
        || merge(chans)(merged)
        || sink(merged) { case (chan, n) => show(f"[$chan%02d->$n%02d@$count%02d]"); count += 1 }
      )
    }

    for { bufSize <- List(0, 1, 5) } {
      val chans: Seq[Chan[(Int, Int)]] = (0 until N).toList.map { case n: Int => Chan[(Int, Int)](s"chan$n", 0) }

      val producers =
        ||(for {chan <- 0 until N} yield source[(Int, Int)](chans(chan), labelled(chan)))

      val merged = Chan[(Int, Int)]("merged", bufSize)

      var count = 0
      println(s"Merge trial $N producers (output termination) (merge channel size = $bufSize)")
      println(s"This also demonstrates selective logging of channel events")
      for { chan <- chans } chan.level = FINEST
      Component.level=FINEST
      merged.level=FINEST
      run(producers             ||
          merge(chans)(merged)  ||
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
        withPorts(l, r, d, c) {
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
        }
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

      def copy[T](i: InPort[T], o: OutPort[T]): process = proc(s"xfer($i,$o)"){
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
        withPorts(l, c, r, d) {
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
          println(s"$l\n$r\n")
        }
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

      val server: process = proc("serve") {
        serve(lev, rev, cev)
        Default.finer(s"serve() terminated")
      }

      run( server
        || source(c, "my dog has fleas".split(' '))
        || source(l, (1 to 20).map(_.toString))
        || source(r, (100 to 120).map(_.toString))
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
        withPorts (l, c, r) {
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




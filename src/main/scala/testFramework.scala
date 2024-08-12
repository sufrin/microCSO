package org.sufrin.microCSO
import org.sufrin.logging._

import org.sufrin.microCSO.Time._

/**
 * Any object that extends `testFramework` has a `main` that can
 * be run (from the terminal or IDE), and that, in turn, runs
 * its defined `test()`. Each `test()` should use
 * `run`, and `apply` (or their logging.Default-enabling
 * variants `frun`, `fapply`) to start processes.
 *
 * 1. `run` and `frun`
 * fork their argument process, and interrupt it if it runs past
 * a deadline; these are helpful for coping with deadlocked
 * threads.
 *
 * 2. `apply` and `fapply` are lower-level testers that
 * catch and backtrace any exceptions/errors thrown by
 * the process they run.
 */
trait testFramework {
  val logging: Boolean = true

  val deadline: Nanoseconds = Time.seconds(4.0)

  /** This method defines an individual test */
  def test(): Unit

  /** Run the defined test */
  def main(args: Array[String]): Unit = test()

  def run(p: process): Unit = {
    CSORuntime.reset()
    Thread.currentThread.setName(s"RUN($p)")
    println(s"============= RUN $p ==============")
    System.out.flush()
    val handle = p.fork()
    handle.terminationStatus(deadline) match {
      case (true, status)  => println(s"\n$p\n TERMINATED ($status)")
      case (false, status) => printStatus(status)
    }
    println(s"=====================")
    System.out.flush()
  }

  def printStatus(status: termination.Status): Unit = {
    println(s"\nTIMEOUT after ${deadline.toDouble/seconds(1.0)} seconds ($status)")
    println("CSO Runtime Information:")
    CSORuntime.forEach {
      case thread: Thread =>
        CSORuntime.remove(thread)
        Threads.showThreadTrace(thread)
    }
    println("Open Channels:")
    CSORuntime.forEachChannel{
      case chan: Chan[_] =>
        println(s"$chan")
    }
  }

  def show(s: String): Unit = {
    if (Default.level>INFO) println(s) else print(s"$s ")
    System.out.flush()
  }

  def frun(p: process): Unit = {
    val l = Default.level
    Default.level=FINEST
    run(p)
    Default.level=l
  }

  def fapply(p: process): Unit = {
    val l = Default.level
    Default.level=FINEST
    apply(p)
    Default.level=l
  }

    def apply(p: process): Unit = {
    println(s"============= APPLY $p ==============")
    try p() catch {
      case exn: Throwable =>
        System.out.println(exn.getMessage)
        Threads.showStackTrace(exn.getStackTrace)
        System.out.flush()
    }
    println(s"===========================")
    System.out.flush()
  }

}

package org.sufrin.microCSO

import Alternation.Serve
import org.sufrin.logging.Loggable

/**
 *  Processes that implement generators and connectors for
 *  inports and outports. Unless otherwise documented
 *  a component respects the network termination protocol
 *  by invoking `closeOut`'s (respectively `closeIn`'s) on its
 *  outports (respectively) inports when it terminates.
 */
object Component extends Loggable{
  import proc._

  /** Copy from the given input stream to the given output streams, performing
   * the outputs concurrently. Terminate when the input stream or any of the
   * output streams is closed.
   * {{{
   * in            /|----> x, ...
   * x, ... >---->{ | : outs
   *               \|----> x, ...
   * }}}
   */
  @inline
  def tee[T](in: InPort[T], outs: OutPort[T]*): process = Tee(in, outs)
  def Tee[T](in: InPort[T], outs: Seq[OutPort[T]]): process = proc("tee") {
    var v       = in.nothing
    val outputs = ||(for (out <- outs) yield proc { out ! v })
    WithPorts(outs,in) { repeatedly { v = in ? (); outputs() } }
  }

  /**
   * Connect 2 inports to an outport of pairs.
   * Terminates on noticing that an inport or outport has
   * closed.
   */
  def zip[A,B](as: InPort[A], bs: InPort[B])(out: OutPort[(A,B)]): proc = proc(s"zip($as,$bs)($out)") {
    var a = as.nothing
    var b = bs.nothing
    val read = proc(s"$as?()") { a = as ? () } || (proc(s"$bs?()") { b = bs ? () })
      withPorts(as,bs,out){
        repeatedly {
          read()
          out ! (a, b)
        }
      }
    if (logging) finer(s"zip($as, $bs)($out) terminated")
  }

  /**
   * Connect 3 inports to an outport of triples.
   * Terminates on noticing that an inport or outport has
   * closed.
   */
  def zip[A,B,C](as: InPort[A], bs: InPort[B], cs: InPort[C])(out: OutPort[(A,B,C)]): proc = proc(s"zip($as,$bs,$cs)($out)") {
    var a = as.nothing
    var b = bs.nothing
    var c = cs.nothing
    val read = ||(proc(s"$as?()") { a = as ? () },
                  proc(s"$bs?()")  { b = bs ? () },
                  proc(s"$cs?()")  { c = cs ? () })
      withPorts(as,bs,cs,out){
        repeatedly {
          read()
          out ! (a, b, c)
        }
      }
      finer(s"zip repeatedly($as, $bs, $cs)($out) terminated")
  }

  /**
   * Connect 4 inports to an outport of quadruples.
   * Terminates on noticing that an inport or outport has
   * closed.
   */
  def zip[A,B,C,D](as: InPort[A], bs: InPort[B], cs: InPort[C], ds: InPort[D])
                  (out: OutPort[(A,B,C,D)]): proc = proc(s"zip($as,$bs,$cs,$ds)($out)") {
    var a = as.nothing
    var b = bs.nothing
    var c = cs.nothing
    var d = ds.nothing
    val read =
      ||(proc(s"$as?()") { a = as ? () },
        proc(s"$bs?()") { b = bs ? () },
        proc(s"$cs?()") { c = cs ? () },
        proc(s"$ds?()") { d = ds ? () },
      )
      withPorts(as,bs,cs,ds,out) {
        repeatedly {
          read()
          out ! (a, b, c, d)
        }
        if (logging) finer(s"zip($as, $bs, $cs, $ds)($out) terminated")
      }
  }

  /**
   * Connect an inport to an outport. Terminates on noticing that either has
   * closed.
   */
  def copy[T](in: InPort[T], out: OutPort[T]): process = proc(s"copy($in, $out)") {
    withPorts(in, out) {
      repeatedly {
        out ! (in ? ())
      }
    }
  }

  /** Outputs incoming data (more or less in order of arrival)
   * from `ins` onto `out`. Terminates when all of `ins` or `out`
   * has terminated
   */
  @inline def merge[T](ins: InPort[T]*)(out: OutPort[T]): process  = Merge(ins)(out)

  def Merge[T](ins: Seq[InPort[T]])(out: OutPort[T]): process  = proc (s"Merge($ins)($out)") {
      WithPorts(ins, out) {
        Serve(
          for {in <- ins} yield in =?=> { t => out ! t }
        )
      }
      if (logging) finer(s"Merge($ins)($out) SERVE terminated")
    }

  /** Write the elements of `it` in sequence to `out`, then `out.closeOut` */
  def source[T](it: Iterable[T])(out: OutPort[T]): proc = proc (s"source($out,...)"){
    var count = 0
    //CSORuntime.newLocal("source count", count)
    //CSORuntime.newLocal("source out", out)
    //withPorts (out) {
      repeatFor (it) { t => out!t; count += 1 }
      out.closeOut()
    //}
    if (logging) finer(s"source($out) terminated after $count")

  }

  /**
   * Repeatedly read from `in` and pass the datum read to `andThen`.
   * Terminates when `in` is closed for input, or when `andThen(t)`
   * invokes `stop` directly, or throws an anticipated termination.
   */
  def sink[T](in: InPort[T])(andThen: T=>Unit): proc = proc (s"sink($in)"){
    var count = 0
    //CSORuntime.newLocal("sink count", count)
    //CSORuntime.newLocal("sink in", in)
    //withPorts(in) {
      repeatedly { in?{ t => andThen(t); count+=1 } }
      in.closeIn()
    //}
    if (logging) finer(s"sink($in) terminated after $count")
  }
}

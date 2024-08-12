package org.sufrin.microCSO

/**
 * Utilities to show the states of running or deadlocked threads (more or less) intelligibly.
 */
object ThreadTracing {
  import java.io.PrintStream

  val suppress: String = "java.base"


  def showThreadTrace(thread: Thread, out: PrintStream) = {
    out.println(thread)
    CSORuntime.forFormat(thread) { state => out.println(state) }
    CSORuntime.forLocals(thread) {
      case (key, value) => out.println(f"$key%8s -> $value%s")
    }
    showStackTrace(thread.getStackTrace, out)
  }

  def showStackTrace(trace: Array[StackTraceElement], out: PrintStream=System.out) = {
    for (frame <- trace
         if ! frame.isNativeMethod
         )
    {
      if (frame.getClassName.startsWith("java.")) {
      }
      else
        out.println(unmangle(frame.toString))
    }
    out.println()
  }

  def showThreadTrace(thread: Thread): Unit =
    showThreadTrace(thread: Thread, System.out)

  /** Mapping from mangleable characters to their mangling. */
  private val mangleMap = List(
    ("~", "$tilde"),
    ("=", "$eq"),
    ("<", "$less"),
    (">", "$greater"),
    ("!", "$bang"),
    ("#", "$hash"),
    ("%", "$percent"),
    ("^", "$up"),
    ("&", "$amp"),
    ("|", "$bar"),
    ("*", "$times"),
    ("/", "$div"),
    ("+", "$plus"),
    ("-", "$minus"),
    (":", "$colon"),
    ("\\", "$bslash"),
    ("?", "$qmark"),
    ("@", "$at")
  )

  /** unmangle a compiler-generated mangled name */
  private def unmangle(name: String): String = {
    var r = name
    for ((ch, mangled) <- mangleMap) r = r.replace(mangled, ch)
    r
  }
}

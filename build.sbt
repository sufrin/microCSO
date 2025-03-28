
ThisBuild / scalaVersion := "2.13.12"
ThisBuild / fork := true



ThisBuild / crossPaths := false
ThisBuild / organization := "org.sufrin"
ThisBuild / name := "microCSO"
ThisBuild / version := "0.9.0"
ThisBuild / artifactName := {
  (sv: ScalaVersion, mod: ModuleID, artifact: Artifact) =>
  "microCSO-" + mod.revision + "." + artifact.extension
}



scalacOptions ++= Seq(
    "-encoding",   "UTF-8",
    "-deprecation",
    "-feature",
    "-language:implicitConversions",
    "-Xfatal-warnings"
  )
  

lazy val root = (project in file("."))
  .settings(
      name := "microCSO",
      idePackagePrefix := Some("org.sufrin.microCSO"),
  )

resolvers += Resolver.file("local-ivy", new File(Path.userHome.absolutePath + "/.ivy2/repository"))(Resolver.ivyStylePatterns)

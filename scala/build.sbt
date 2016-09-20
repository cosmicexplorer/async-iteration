lazy val root = (project in file(".")).
  settings(
    name := "async_iteration",
    version := "0.1",
    scalaVersion := "2.11.8",
    scalacOptions := Seq(
      "-deprecation", "-unchecked", "-feature", "-encoding", "utf8",
      "-Yno-adapted-args", "-Ywarn-dead-code", "-Ywarn-numeric-widen",
      "-Ywarn-value-discard", "-Ywarn-infer-any", "-Ywarn-unused-import",
      "-Xlint", "-language:higherKinds"
    ),
    libraryDependencies += "org.scalatest" % "scalatest_2.11" % "2.2.4" % "test"
  )

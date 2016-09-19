lazy val root = (project in file(".")).
  settings(
    name := "async_iteration",
    version := "0.1",
    scalaVersion := "2.11.8",
    libraryDependencies += "org.scalatest" % "scalatest_2.11" % "2.2.4" % "test"
  )

val _scalaVersion = "3.9.0"

ThisBuild / organization := "io.github.makingthematrix"
name := "signals3"
ThisBuild / homepage := Some(uri("https://github.com/makingthematrix/signals3"))
ThisBuild / licenses := Seq("GPL 3.0" -> uri("https://www.gnu.org/licenses/gpl-3.0.en.html"))
ThisBuild / scalaVersion := _scalaVersion
ThisBuild / versionScheme := Some("semver-spec")
Test / scalaVersion := _scalaVersion
ThisBuild / description := "A lightweight event streaming library for Scala"

val standardOptions = Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-encoding",
  "utf8"
)

val scala3Options = Seq(
  "-explain",
  "-Wunused:all",
  "-no-indent"
)

scmInfo := Some(
  ScmInfo(
    uri("https://github.com/makingthematrix/signals3"),
    "scm:git:git@github.com:makingthematrix/signals3.git"
  )
)

developers := List(
  Developer(
    "makingthematrix",
    "Maciej Gorywoda",
    "makingthematrix@protonmail.com",
    uri("https://github.com/makingthematrix"))
)

lazy val root = (project in file("."))
  .settings(
    name := "signals3",
    libraryDependencies ++= Seq(
      //Test dependencies
      "org.scalameta" %% "munit" % "1.3.5" % "test"
    ),
    scalacOptions ++= standardOptions ++ scala3Options
  )

testFrameworks += new TestFramework("munit.Framework")
Test / parallelExecution := true
fork := true
Test / fork := true

javaOptions ++= Seq("--sun-misc-unsafe-memory-access=allow")

// new setting for the Central Portal
ThisBuild / publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}
// Remove all additional repository other than Maven Central from POM
ThisBuild / pomIncludeRepository := { _ => false }
ThisBuild / publishMavenStyle := true
ThisBuild / exportJars := true
ThisBuild / isSnapshot := false

credentials += Credentials(Path.userHome / ".sbt" / "sonatype_central_credentials")

ThisBuild / sbtPluginPublishLegacyMavenStyle := false
// sbt publishSigned
// sbt sonaUpload
// sbt sonaRelease

Compile / packageBin / packageOptions +=
  Package.ManifestAttributes("Automatic-Module-Name" -> "signals3")

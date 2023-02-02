// based on http://caryrobbins.com/dev/sbt-publishing/

val _scalaVersion = "3.2.1"

organization := "io.makingthematrix"
name := "signals3"
homepage := Some(url("https://github.com/makingthematrix/signals3"))
licenses := Seq("GPL 3.0" -> url("https://www.gnu.org/licenses/gpl-3.0.en.html"))
ThisBuild / scalaVersion := _scalaVersion
ThisBuild / versionScheme := Some("semver-spec")
Test / scalaVersion := _scalaVersion

val standardOptions = Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-encoding",
  "utf8"
)

val scala3Options = Seq(
  "-explain",
  "-rewrite"
)

publishMavenStyle := true
Test / publishArtifact := false
pomIncludeRepository := { _ => false }
ThisBuild / publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases" at nexus + "service/local/staging/deploy/maven2")
}

scmInfo := Some(
  ScmInfo(
    url("https://github.com/makingthematrix/signals3"),
    "scm:git:git@github.com:makingthematrix/signals3.git"
  )
)

developers := List(
  Developer(
    "makingthematrix",
    "Maciej Gorywoda",
    "makingthematrix@protonmail.com",
    url("https://github.com/makingthematrix"))
)

resolvers ++= Seq(
  Resolver.sonatypeRepo("releases"),
  Resolver.sonatypeRepo("public"),
  Resolver.mavenLocal
)

publishMavenStyle := true

publishConfiguration := publishConfiguration.value.withOverwrite(true)
publishLocalConfiguration := publishLocalConfiguration.value.withOverwrite(true)
publishM2Configuration := publishM2Configuration.value.withOverwrite(true)

lazy val root = (project in file("."))
  .settings(
    name := "signals3",
    libraryDependencies ++= Seq(
      //Test dependencies
      "org.scalameta" %% "munit" % "0.7.29" % "test"
    ),
    scalacOptions ++= standardOptions ++ scala3Options
  )

testFrameworks += new TestFramework("munit.Framework")

exportJars := true
Compile / packageBin / packageOptions +=
  Package.ManifestAttributes("Automatic-Module-Name" -> "signals3")

usePgpKeyHex(sys.env.getOrElse("PGP_KEY_HEX", ""))

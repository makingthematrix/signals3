// based on http://caryrobbins.com/dev/sbt-publishing/

val _scalaVersion = "3.7.2"

organization := "io.github.makingthematrix"
sonatypeProfileName := "io.github.makingthematrix"
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
  "-Wsafe-init",
  "-Ycheck-all-patmat",
  "-Wunused:imports",
  "-no-indent", "-rewrite"
)

publishMavenStyle := true
Test / publishArtifact := false
pomIncludeRepository := { _ => false }
ThisBuild / publishTo := sonatypePublishToBundle.value
// For all Sonatype accounts created on or after February 2021
ThisBuild / sonatypeCredentialHost := "s01.oss.sonatype.org"

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

resolvers ++=
  Resolver.sonatypeOssRepos("releases") ++
  Resolver.sonatypeOssRepos("public") ++
  Seq(Resolver.mavenLocal)

publishMavenStyle := true

publishConfiguration := publishConfiguration.value.withOverwrite(true)
publishLocalConfiguration := publishLocalConfiguration.value.withOverwrite(true)
publishM2Configuration := publishM2Configuration.value.withOverwrite(true)

lazy val root = (project in file("."))
  .settings(
    name := "signals3",
    libraryDependencies ++= Seq(
      //Test dependencies
      "org.scalameta" %% "munit" % "1.2.0" % "test"
    ),
    scalacOptions ++= standardOptions ++ scala3Options
  )

testFrameworks += new TestFramework("munit.Framework")

exportJars := true
Compile / packageBin / packageOptions +=
  Package.ManifestAttributes("Automatic-Module-Name" -> "signals3")

usePgpKeyHex(sys.env.getOrElse("PGP_KEY_HEX", ""))

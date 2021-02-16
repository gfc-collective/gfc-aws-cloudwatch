import scoverage.ScoverageKeys

name := "gfc-aws-cloudwatch"

organization := "org.gfccollective"

scalaVersion := "2.13.4"

crossScalaVersions := Seq(scalaVersion.value, "2.12.12")

val awsLibVersion = "2.15.77"

scalacOptions += "-target:jvm-1.8"

javacOptions ++= Seq("-source", "1.8", "-target", "1.8")

ScoverageKeys.coverageFailOnMinimum := true

ScoverageKeys.coverageMinimum := 15.0

libraryDependencies ++= Seq(
  "org.scala-lang.modules" %% "scala-java8-compat" % "0.9.1",
  "org.gfccollective" %% "gfc-logging" % "1.0.0",
  "org.gfccollective" %% "gfc-concurrent" % "1.0.0",
  "software.amazon.awssdk" % "cloudwatch" % awsLibVersion,
  "software.amazon.awssdk" % "cloudwatchlogs" % awsLibVersion,
  "org.specs2" %% "specs2-scalacheck" % "4.10.6" % Test,
)

releaseCrossBuild := true

releasePublishArtifactsAction := PgpKeys.publishSigned.value

publishMavenStyle := true

publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases"  at nexus + "service/local/staging/deploy/maven2")
}

publishArtifact in Test := false

pomIncludeRepository := { _ => false }

licenses := Seq("Apache-style" -> url("https://raw.githubusercontent.com/gfc-collective/gfc-aws-cloudwatch/master/LICENSE"))

homepage := Some(url("https://github.com/gfc-collective/gfc-aws-cloudwatch"))

pomExtra := <scm>
  <url>https://github.com/gfc-collective/gfc-aws-cloudwatch.git</url>
  <connection>scm:git:git@github.com:gfc-collective/gfc-aws-cloudwatch.git</connection>
</scm>
<developers>
  <developer>
    <id>gheine</id>
    <name>Gregor Heine</name>
    <url>https://github.com/gheine</url>
  </developer>
  <developer>
    <id>andreyk0</id>
    <name>Andrey Kartashov</name>
    <url>https://github.com/andreyk0</url>
  </developer>
</developers>

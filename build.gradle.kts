plugins {
    scala
    application
}

repositories {
    mavenCentral()
}

val scalaMajor by extra {  "2.13" }
val scalaMinor by extra { "5" }
val scalaVersion by extra { "2.13.5" }
val akkaVersion by extra {  "2.6.14" }

dependencies {
    implementation("org.scala-lang:scala-library:$scalaVersion")
    implementation("com.typesafe.akka:akka-actor-typed_$scalaMajor:$akkaVersion")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("it.unibo.aggrcompare.Aggregate")
}
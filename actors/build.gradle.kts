plugins {
    scala
    application
}

repositories {
    mavenCentral()
}

val scalaMajor by extra {  "2.13" }
val scalaMinor by extra { "2" }
val scalaVersion = "$scalaMajor.$scalaMinor"
val akkaVersion by extra {  "2.6.14" }

dependencies {
    implementation("org.scala-lang:scala-library:$scalaVersion")
    implementation("com.typesafe.akka:akka-actor-typed_$scalaMajor:$akkaVersion")
    implementation("it.unibo.scafi:scafi-simulator_$scalaMajor:0.3.3")
    runtimeOnly("ch.qos.logback:logback-classic:1.2.3")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("it.unibo.aggrcompare.Aggregate")
}
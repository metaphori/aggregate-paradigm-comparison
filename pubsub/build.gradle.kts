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
    implementation("it.unibo.scafi:scafi-simulator_$scalaMajor:0.3.3")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.12.2")
    implementation("io.vertx:vertx-core:4.1.2")
    // implementation("io.vertx:vertx-json-schema:4.1.2")
    //implementation("io.vertx:vertx-lang-scala_$scalaMajor:3.9.1") // requires Scala 2.12
    runtimeOnly("ch.qos.logback:logback-classic:1.2.3")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("it.unibo.aggrcompare.PubSubVertx")
}
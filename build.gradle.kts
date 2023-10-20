plugins {
    kotlin("jvm") version "1.9.20-RC"
    application
}

group = "info.benjaminhill"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven {
        url = uri("https://jitpack.io")
    }
    maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots") }
}


dependencies {
    implementation("org.bytedeco:javacv-platform:1.5.10-SNAPSHOT")

    // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-coroutines-core
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("info.benjaminhill.videosmush.ScriptedLapseKt")
}
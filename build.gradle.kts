plugins {
    kotlin("jvm") version "2.2.21"
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
    implementation("org.bytedeco:javacv-platform:1.5.12")

    // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-coroutines-core
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    implementation("org.jocl:jocl:2.0.4")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("info.benjaminhill.videosmush.MainKt")
}
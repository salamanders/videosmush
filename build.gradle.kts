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

    /*
    mavenLocal()
    maven { url = uri("https://dl.bintray.com/kotlin/kotlin-eap") }
    maven { url = uri("https://kotlin.bintray.com/kotlinx") }
    maven { url = uri("https://repository.apache.org/content/groups/snapshots/") }
    maven { url = uri("https://repo.maven.apache.org/maven2/") }
     */
}


dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.20-RC")

    // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-coroutines-core
    //implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.bytedeco:javacv-platform:1.5.10-SNAPSHOT")

    // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-coroutines-core
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    /*
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.0")
    implementation("org.bytedeco:javacv-platform:1.5.9")
    implementation("com.natpryce:konfig:1.6.10.0")
    implementation("org.nield:kotlin-statistics:1.2.1")
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.7.3")
    implementation("com.github.salamanders:utils:583a8dc26e")
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    testImplementation(kotlin("test"))
    */
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("info.benjaminhill.videosmush.ScriptedLapseKt")
}
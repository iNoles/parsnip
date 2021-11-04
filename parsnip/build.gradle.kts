plugins {
    kotlin("jvm") version "1.5.31"
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    api("com.squareup.okio:okio:3.0.0")

    testImplementation("junit:junit:4.13.2")
}
plugins {
    kotlin("jvm") version "1.5.31"
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation(project(":parsnip"))
    implementation("com.squareup.retrofit2:retrofit:2.3.0")
}
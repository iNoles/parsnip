plugins {
    kotlin("jvm")
    kotlin("kapt")
}

dependencies {
    implementation(project(":parsnip"))
    api("com.squareup:kotlinpoet-metadata:1.12.0")

    compileOnly("net.ltgt.gradle.incap:incap:0.3")
    kapt("net.ltgt.gradle.incap:incap-processor:0.3")

    implementation("com.google.auto.service:auto-service:1.0.1")
    kapt("com.google.auto.service:auto-service:1.0.1")

    testImplementation("junit:junit:4.13.2")
}
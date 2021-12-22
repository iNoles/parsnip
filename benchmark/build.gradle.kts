plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":parsnip"))

    implementation("com.google.caliper:caliper:1.0-beta-3")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml:2.13.0")
    implementation("org.simpleframework:simple-xml:2.7.1")

    implementation("com.tickaroo.tikxml:annotation:0.8.13")
    implementation("com.tickaroo.tikxml:core:0.8.13")
    annotationProcessor("com.tickaroo.tikxml:processor:0.8.13")
}
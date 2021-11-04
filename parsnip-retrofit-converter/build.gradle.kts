plugins {
    java
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
    implementation(project(":parsnip"))
    implementation("com.squareup.retrofit2:retrofit:2.3.0")
}
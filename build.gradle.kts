plugins {
    kotlin("jvm") version "1.3.61"
    maven
    `maven-publish`
}

group = "com.github.trashkalmar"
version = "1.0"

repositories {
    jcenter()
    maven("https://jitpack.io")
}

dependencies {
    implementation(kotlin("stdlib"))
}

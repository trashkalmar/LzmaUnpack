repositories {
  mavenCentral()
  maven(url = "https://jitpack.io")
}


plugins {
  kotlin("jvm") version "1.5.0"
  `maven-publish`
}

group = "com.github.trashkalmar"
version = "1.0.1"

dependencies {
  implementation(kotlin("stdlib"))
}

plugins {
  kotlin("jvm") version "1.5.0"
  `maven-publish`
}

allprojects {
  repositories {
    mavenCentral()
  }
}

group = "com.github.trashkalmar"
version = "1.0.1"

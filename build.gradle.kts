import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  kotlin("jvm") version "1.8.0"
  id("org.jetbrains.intellij") version "1.13.3"
}

repositories {
  mavenCentral()
}

group = "com.jetbrains"
version = "1.6.0"

// https://github.com/JetBrains/gradle-intellij-plugin/
// https://www.jetbrains.com/intellij-repository/snapshots/
// Ex: "IC-2021.1", "IC-221.4501-EAP-CANDIDATE-SNAPSHOT"
intellij {
  version.set("IC-221.5080.210")
  updateSinceUntilBuild.set(false)
}

tasks.withType<KotlinCompile> {
  kotlinOptions.jvmTarget = JavaVersion.VERSION_11.toString()
  kotlinOptions.freeCompilerArgs = listOf("-Xjvm-default=enable")
}

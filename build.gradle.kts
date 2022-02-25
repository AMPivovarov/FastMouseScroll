import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  kotlin("jvm") version "1.5.10"
  id("org.jetbrains.intellij") version "1.4.0"
}

repositories {
  mavenCentral()
}

group = "com.jetbrains"
version = "1.5.9"

// https://github.com/JetBrains/gradle-intellij-plugin/
// https://www.jetbrains.com/intellij-repository/snapshots/
// Ex: "IC-2021.1", "IC-221.4501-EAP-CANDIDATE-SNAPSHOT"
intellij {
  version.set("IC-221.4842-EAP-CANDIDATE-SNAPSHOT")
  updateSinceUntilBuild.set(false)
}

tasks.withType<KotlinCompile> {
  kotlinOptions.jvmTarget = JavaVersion.VERSION_11.toString()
  kotlinOptions.freeCompilerArgs = listOf("-Xjvm-default=enable")
}

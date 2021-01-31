plugins {
  kotlin("jvm") version "1.3.72"
  id("org.jetbrains.intellij") version "0.4.8"
}

repositories {
  mavenCentral()
}

group = "com.jetbrains"
version = "1.5.7"

// See https://github.com/JetBrains/gradle-intellij-plugin/
intellij {
  version = "IC-2021.1"
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
  kotlinOptions.jvmTarget = "1.8"
  kotlinOptions.freeCompilerArgs = listOf("-Xjvm-default=enable")
}

tasks {
  patchPluginXml {
    // do not patch supported versions
    sinceBuild(null)
    untilBuild(null)
  }
}
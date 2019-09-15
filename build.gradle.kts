plugins {
  kotlin("jvm") version "1.3.31"
  id("org.jetbrains.intellij") version "0.4.8"
}

repositories {
  mavenCentral()
}

group = "com.jetbrains"
version = "1.5.1"

// See https://github.com/JetBrains/gradle-intellij-plugin/
intellij {
  version = "IC-2019.1"
}

tasks {
  patchPluginXml {
    // do not patch supported versions
    sinceBuild(null)
    untilBuild(null)
  }
}
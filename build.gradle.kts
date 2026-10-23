plugins {
    java
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.fenbi.plugin"
version = "0.8.1"

repositories {
    mavenCentral()
    maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
}

dependencies {
    compileOnly("com.google.code.gson:gson:2.10.1")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

val localIdeaPath = providers.gradleProperty("intellijLocalPath").orNull

intellij {
    if (localIdeaPath.isNullOrBlank()) {
        version.set("2024.3")
    } else {
        localPath.set(localIdeaPath)
    }
    plugins.set(emptyList<String>())
    instrumentCode.set(false)
    updateSinceUntilBuild.set(false)
    downloadSources.set(false)
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(17)
    }
    buildSearchableOptions {
        enabled = false
    }
    patchPluginXml {
        sinceBuild.set("233")
        untilBuild.set("243.*")
    }
    runIde {
        jvmArgs("-Xmx2g")
    }
}

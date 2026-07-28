pluginManagement {
    // The Kotlin plugin marker is published on Maven Central. Keeping this
    // included build independent from plugins.gradle.org makes JitPack builds
    // resilient when the Gradle Plugin Portal is temporarily unreachable.
    repositories {
        mavenCentral()
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

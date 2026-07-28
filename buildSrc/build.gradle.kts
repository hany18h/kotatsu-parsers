plugins {
    alias(libs.plugins.kotlin.jvm)
}

repositories {
    mavenCentral()
}

kotlin {
	jvmToolchain(17)
}

dependencies {
    implementation(gradleApi())
    implementation(libs.korte)
    implementation(libs.simplexml)
    implementation(libs.kotlinx.coroutines.core)
}

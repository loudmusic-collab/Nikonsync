import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":ptpip"))
    implementation(testFixtures(project(":ptpip")))
    implementation(libs.kotlinx.coroutines.core)
}

application {
    applicationName = "nikonsync-cli"
    mainClass.set("com.loudmusic.nikonsync.cli.MainKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    workingDir = rootProject.projectDir
}

import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.3.5")
        plugin("Dart:508.1.0")
        plugin("io.flutter:95.0.0")
        testFramework(TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here, for example:
        // bundledPlugin("com.intellij.java")
    }
}

// IDEA 253's bundled Vue LSP cannot resolve its resource directory under the test classloader.
// It is unrelated to Dart/Flutter; isolate only the test sandbox, never the user's/runIde plugins.
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>("prepareTestSandbox") {
    disabledPlugins.add("org.jetbrains.plugins.vue")
}

tasks.named<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>("runIde") {
    if (providers.gradleProperty("testExplorerDebug").isPresent) {
        systemProperty("idea.log.debug.categories", "#dev.igorshahin")
    }
}

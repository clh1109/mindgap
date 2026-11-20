pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // sherpa-onnx 官方预编译 AAR 由 JitPack 转发布
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "MindGap"
include(":app")

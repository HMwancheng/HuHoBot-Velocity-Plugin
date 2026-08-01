// HuHoBot-Velocity-Plugin - 仅包含 Velocity/Proxy 模块
// 基于 HuHoBot (https://github.com/HuHoBot/KotlinMergeAdapter) 修改

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
    }
}

pluginManagement {
    repositories {
        maven { url = uri("https://maven.fabricmc.net/") }
        maven { url = uri("https://maven.architectury.dev/") }
        maven { url = uri("https://maven.minecraftforge.net/") }
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

include(":common-Bot")
project(":common-Bot").projectDir = file("common/Bot")
include(":server-Proxy")
project(":server-Proxy").projectDir = file("server/Proxy")

rootProject.name = "HuHoBot-Velocity-Plugin"
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
        // Onyx BOOX SDK, needed once we switch the canvas to the Pen SDK for
        // low-latency e-ink drawing (their repo is http-only):
        // maven {
        //     url = uri("http://repo.boox.com/repository/maven-public/")
        //     isAllowInsecureProtocol = true
        // }
    }
}

rootProject.name = "weekly-shop"
include(":app")

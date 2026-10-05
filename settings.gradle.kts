pluginManagement {
    repositories {
        maven("https://redirector.gvt1.com/edgedl/android/maven2/")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://redirector.gvt1.com/edgedl/android/maven2/")
        google()
        mavenCentral()
    }
}

rootProject.name = "EtingMobile"
include(":mobile", ":wear")

pluginManagement {
    repositories {
        google()
        // Google's official mirror of Maven Central (byte-identical, so dependency verification is unchanged).
        // Used first because Maven Central throttles CI runners.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralMirror" }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's official mirror of Maven Central (byte-identical, so dependency verification is unchanged).
        // Used first because Maven Central throttles CI runners.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralMirror" }
        mavenCentral()
    }
}
rootProject.name = "Spatia"
include(":core-dsp", ":app")

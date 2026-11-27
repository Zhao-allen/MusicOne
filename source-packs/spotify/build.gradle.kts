plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }

android {
    namespace = "com.musicone.source.spotify"
    compileSdk = 37
    androidResources { additionalParameters += listOf("--package-id", "0x76", "--allow-reserved-package-id") }
    defaultConfig {
        applicationId = "com.musicone.source.spotify"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"" + providers.gradleProperty("spotifyClientId").getOrElse("") + "\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    signingConfigs.getByName("debug") {
        enableV1Signing = true
        enableV2Signing = true
    }
}

dependencies {
    if (findProject(":source-sdk") != null) compileOnly(project(":source-sdk"))
    else compileOnly(files(providers.gradleProperty("sourceSdk").get()))
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
    compileOnly("androidx.compose.runtime:runtime:1.11.4")
    compileOnly("androidx.compose.ui:ui:1.11.4")
    compileOnly("androidx.compose.foundation:foundation:1.11.4")
    compileOnly("androidx.compose.material3:material3:1.4.0")
    testImplementation("junit:junit:4.13.2")
}

configurations.configureEach {
    if (name.endsWith("RuntimeClasspath") && !name.contains("UnitTest", ignoreCase = true)) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    }
}

tasks.register<Sync>("packageDebugSource") {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.dir("outputs/apk/debug")) { include("*.apk") }
    into(layout.buildDirectory.dir("outputs/source-packs/debug"))
    rename { "spotify-0.1.0.mscm" }
}

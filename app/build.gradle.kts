plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.she.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.she.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

// Launcher icons are stored as base64 text in app/icons-b64/ because the
// GitHub API used to push this repo cannot upload binary files (it stores
// base64 as literal text, double-encoding the PNG). This task decodes them
// into src/main/res/ at build time, before resources are merged, so the
// Furret launcher icon and splash image are real PNGs in the built APK.
// The .b64 files are left in the repo; the decoded PNGs are build outputs
// and are not committed.
tasks.register("decodeIcons") {
    doLast {
        // NOTE: bare file("...") / project.file("...") and a bare `return`
        // do NOT work inside a doLast {} action in the Kotlin DSL — the
        // lambda receiver is the Task, not the Project, so file() is
        // unresolved and `return` is illegal there. That broke script
        // compilation and failed the "Build debug APK" step
        // (run 43010930801). Per Alex's correction, resolve all paths
        // through project.layout.projectDirectory explicitly, and use
        // return@doLast. .dir() for directories, .file() for files.
        val iconsDir = project.layout.projectDirectory.dir("icons-b64").asFile
        if (!iconsDir.exists()) return@doLast
        iconsDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".png.b64") }
            .forEach { b64File ->
                val rel = b64File.relativeTo(iconsDir).path.removeSuffix(".b64")
                val out = project.layout.projectDirectory.file("src/main/res/$rel").asFile
                out.parentFile.mkdirs()
                out.writeBytes(java.util.Base64.getMimeDecoder().decode(b64File.readText()))
            }
    }
}
tasks.named("preBuild") { dependsOn("decodeIcons") }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.5")
    // Coroutines are used to poll the bridge (127.0.0.1:8080/state)
    // while the splash screen is showing.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

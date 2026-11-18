import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun loadProperties(name: String): Properties? =
    rootProject.file(name).takeIf { it.exists() }?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

// Version lives in version.properties as major.minor.patch (Major.Big_changes.bug_fixes).
val versionFile = rootProject.file("version.properties")
val appVersion = loadProperties("version.properties")!!
val vMajor = appVersion.getProperty("major").toInt()
val vMinor = appVersion.getProperty("minor").toInt()
val vPatch = appVersion.getProperty("patch").toInt()
val appVersionName = "$vMajor.$vMinor.$vPatch"

val releaseSigning = loadProperties("keystore.properties")

android {
    namespace = "com.hari.screentime"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hari.screentime"
        minSdk = 28 // KEYGUARD_SHOWN / KEYGUARD_HIDDEN usage events need API 28
        targetSdk = 35
        versionCode = vMajor * 10_000 + vMinor * 100 + vPatch
        versionName = appVersionName
        manifestPlaceholders["appLabel"] = "FT-Tracker"
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = rootProject.file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Separate app id so the debug and release builds can be installed side by side.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "FT-Tracker Debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
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
        compose = true
    }
    lint {
        // Newer AndroidX releases require AGP 9.1 / compileSdk 37; versions are pinned to this toolchain.
        disable += "GradleDependency"
        // targetSdk 36+ needs compileSdk 36+, which this AGP (8.7) does not support; raise both together.
        disable += "OldTargetApi"
        warningsAsErrors = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}

// ---- Version bumping: ./gradlew bumpMajor | bumpMinor | bumpPatch ----

fun registerBump(name: String, description: String, next: () -> Triple<Int, Int, Int>) =
    tasks.register(name) {
        group = "versioning"
        this.description = description
        doLast {
            val (major, minor, patch) = next()
            require(minor < 100 && patch < 100) { "minor and patch must stay below 100" }
            versionFile.writeText(
                versionFile.readText()
                    .replace(Regex("(?m)^major=.*$"), "major=$major")
                    .replace(Regex("(?m)^minor=.*$"), "minor=$minor")
                    .replace(Regex("(?m)^patch=.*$"), "patch=$patch")
            )
            println("Version $appVersionName -> $major.$minor.$patch")
        }
    }

registerBump("bumpMajor", "Major release: x.0.0") { Triple(vMajor + 1, 0, 0) }
registerBump("bumpMinor", "Big changes: 1.x.0") { Triple(vMajor, vMinor + 1, 0) }
registerBump("bumpPatch", "Bug fixes: 1.0.x") { Triple(vMajor, vMinor, vPatch + 1) }

// ---- Archive every built APK into <root>/build/<variant>/, keeping the newest 3 per variant ----

val keepBuilds = 3

listOf("debug", "release").forEach { variant ->
    val cap = variant.replaceFirstChar { it.uppercase() }
    val archive = tasks.register("archive${cap}Apk") {
        group = "build"
        description = "Copies the $variant APK to build/$variant and keeps the newest $keepBuilds"
        val apkDir = layout.buildDirectory.dir("outputs/apk/$variant")
        val outDir = rootProject.layout.buildDirectory.dir(variant)
        doLast {
            val apk = apkDir.get().asFile.listFiles { f -> f.extension == "apk" }?.maxByOrNull { it.lastModified() }
                ?: error("No $variant APK found in ${apkDir.get().asFile}")
            val dest = outDir.get().asFile.apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
            val name = "FT-Tracker-v$appVersionName-$variant-$stamp.apk"
            apk.copyTo(dest.resolve(name), overwrite = true)
            println("Archived: build/$variant/$name")
            dest.listFiles { f -> f.extension == "apk" }!!
                .sortedByDescending { it.lastModified() }
                .drop(keepBuilds)
                .forEach { old ->
                    old.delete()
                    println("Removed old build: build/$variant/${old.name}")
                }
        }
    }
    tasks.matching { it.name == "assemble$cap" }.configureEach { finalizedBy(archive) }
}

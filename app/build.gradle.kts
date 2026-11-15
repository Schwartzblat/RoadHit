plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.roadhit"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.roadhit"
        minSdk = 33
        targetSdk = 36
        // The release workflow passes the version from the tag: -PversionCode=… -PversionName=…
        versionCode = providers.gradleProperty("versionCode").map(String::toInt).getOrElse(1)
        versionName = providers.gradleProperty("versionName").getOrElse("0.1")
    }

    // Release signing comes only from the environment (the release workflow's secrets); the
    // keystore never lives in the repository. Without these variables the release build is unsigned.
    val keystore = providers.environmentVariable("SIGNING_KEYSTORE").orNull
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    lint {
        // No MEDIA_PLAY_FROM_SEARCH activity: in the car lab (Task 3) declaring one did not
        // make the assistant offer RoadHit (see the plan's Decision Log).
        disable += "MissingIntentFilterForMediaSearch"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// The lab's recorder reads testdata/voice/lines.json as the debug asset `lines.json`.
// Only that file is packaged (the directory will also hold the recorded WAVs).
abstract class CopyLabAssets : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val lines: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        lines.get().asFile.copyTo(out.resolve("lines.json"))
    }
}

val copyLabAssets = tasks.register<CopyLabAssets>("copyLabAssets") {
    lines.set(rootProject.layout.projectDirectory.file("testdata/voice/lines.json"))
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyLabAssets, CopyLabAssets::outputDir)
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.car.app)
    implementation(libs.car.app.projected)
    // MediaSessionCompat.Token, which the car app's MediaPlaybackManager takes.
    implementation(libs.media.compat)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

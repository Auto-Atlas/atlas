import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// FCM push-wake: apply the google-services plugin ONLY when the Firebase config exists. The app
// MUST build today without google-services.json (the operator sets up Firebase separately). When they drop
// app/google-services.json in, this auto-applies and FCM goes live — no other change needed.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// ---- Play upload signing -------------------------------------------------------------------------
// Reads the eve.upload.* credentials from local.properties (gitignored), or the EVE_UPLOAD_* env
// vars as a CI fallback. If none are present the release build stays UNSIGNED and we warn LOUDLY —
// the build MUST still succeed on machines without the keystore (never a silent failure). The
// keystore itself lives OUTSIDE the repo and is never committed.
val uploadProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun uploadCred(key: String, env: String): String? =
    (uploadProps.getProperty(key) ?: System.getenv(env))?.takeIf { it.isNotBlank() }
val uploadStoreFile = uploadCred("eve.upload.storeFile", "EVE_UPLOAD_STORE_FILE")
val uploadStorePassword = uploadCred("eve.upload.storePassword", "EVE_UPLOAD_STORE_PASSWORD")
val uploadKeyAlias = uploadCred("eve.upload.keyAlias", "EVE_UPLOAD_KEY_ALIAS")
val uploadKeyPassword = uploadCred("eve.upload.keyPassword", "EVE_UPLOAD_KEY_PASSWORD")
val hasUploadSigning = uploadStoreFile != null && uploadStorePassword != null &&
    uploadKeyAlias != null && uploadKeyPassword != null && file(uploadStoreFile).exists()
if (!hasUploadSigning &&
    gradle.startParameter.taskNames.any { it.contains("Release", true) || it.contains("bundle", true) }
) {
    logger.lifecycle("EVE :app — release signing not configured — AAB will be unsigned (set eve.upload.* in local.properties or EVE_UPLOAD_* env vars).")
}

android {
    namespace = "app.eve"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.atlas"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        // stream-webrtc-android ships 4 ABIs; keep arm64-v8a (real phone) + x86_64 (emulator),
        // drop dead armeabi-v7a/x86 (~30 MB saved).
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    signingConfigs {
        // Created only when the eve.upload.* credentials resolve — otherwise release stays unsigned.
        if (hasUploadSigning) {
            create("release") {
                storeFile = file(uploadStoreFile!!)
                storePassword = uploadStorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasUploadSigning) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
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

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        baseline = file("lint-baseline.xml")
        warningsAsErrors = false
        abortOnError = true
    }
}

composeCompiler {
    // Mark our effectively-immutable third-party types (kotlinx JSON) stable, so strong-skipping
    // compares Approval/ApprovalCardState by equals() instead of identity — a rebuilt-but-equal
    // card then skips recomposition.
    stabilityConfigurationFile.set(layout.projectDirectory.file("compose_stability.conf"))
    // Opt-in skippability/stability reports:
    //   ./gradlew :app:assembleRelease -PcomposeReports=true  → app/build/compose_compiler/*.txt
    if (providers.gradleProperty("composeReports").orNull == "true") {
        reportsDestination = layout.buildDirectory.dir("compose_compiler")
        metricsDestination = layout.buildDirectory.dir("compose_compiler")
    }
}

dependencies {
    // Health Connect's connect-client drags in FULL guava (real ListenableFuture) alongside the empty
    // `com.google.guava:listenablefuture` stub that CameraX pulls. Without this rule the empty stub can
    // win and shadow com.google.common.util.concurrent.ListenableFuture, breaking the CameraX capture
    // code. Google's canonical fix: declare the stub as provided-by guava so guava's real class wins.
    modules {
        module("com.google.guava:listenablefuture") {
            replacedBy("com.google.guava:guava", "listenablefuture is provided by full guava")
        }
    }

    // Pure DTOs + JSON parsing (app.eve.data.models.*) live in :shared, shared with the watch app.
    // Package names are unchanged (app.eve.data.models), so no import churn here.
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.websockets)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.stream.webrtc.android)

    // Wearable Data Layer — the phone is the watch's gateway to approval_api. WearBridgeService
    // (WearableListenerService) receives watch actions; GmsWearGateway writes snapshots + results.
    implementation(libs.play.services.wearable)

    // MainActivity's ActivityResult permission request (registerForActivityResult) requires
    // fragment >= 1.3.0 at runtime (InvalidFragmentVersionForActivityResult lint); a transitive
    // dependency drags in an older fragment. A version CONSTRAINT bumps it WITHOUT adding a direct
    // fragment dependency — same approach the :wear module uses.
    constraints {
        implementation(libs.androidx.fragment)
    }

    // Firebase Cloud Messaging — lets the server wake the phone for the morning ritual even when
    // the app was killed. The BoM aligns versions; firebase-messaging compiles WITHOUT
    // google-services.json and stays inert (no token) until the json + plugin are present.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // Drop-in QR scanner for "Scan to connect" pairing (no hardcoded creds).
    implementation(libs.zxing.embedded)

    // CameraX — look_via_phone still capture. Bound to the foreground activity's lifecycle only;
    // there is no background camera path (see app.eve.vision.FrameCaptureController).
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Health Connect (EVE Health v1). connect-client reads the on-phone health hub; WorkManager runs
    // the periodic + on-demand snapshot upload. All androidx.health.connect.* usage is confined to
    // app.eve.health.HealthConnectReader / HealthConnectManager (behind the HealthSnapshotReader seam).
    implementation(libs.androidx.health.connect.client)
    implementation(libs.androidx.work.runtime.ktx)

    // ------------------------------------------------------------------------------------------
    // Meta Wearables Device Access Toolkit (DAT) — glasses camera + (BT-routed) audio.
    //
    // DELIBERATELY COMMENTED OUT. DAT 0.8.0 is a token-gated developer preview published to
    // GitHub Packages (NOT Maven Central / Google), so it is NOT anonymously resolvable and cannot
    // ship in a public app yet (GA slated 2026). The app builds green on the StubGlassesCameraSource
    // until this is uncommented. Exact coordinates + repo, so the day it's wired in is a one-liner:
    //
    //   Repo (add to settings.gradle.kts dependencyResolutionManagement.repositories — see the note
    //   there), authenticated with a GitHub PAT (read:packages) from local.properties `github_token`
    //   or the GITHUB_TOKEN env var (NEVER hardcode the token):
    //     maven("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
    //
    //   implementation("com.meta.wearable:mwdat-core:0.8.0")
    //   implementation("com.meta.wearable:mwdat-camera:0.8.0")
    //   // debugImplementation("com.meta.wearable:mwdat-mockdevice:0.8.0") // test w/o hardware
    //
    // Then rename app/src/main/java/app/eve/glasses/gated/RealGlassesCameraSource.kt.gated → .kt,
    // set GlassesToolkit.IS_BUNDLED = true, and bind Real* in AppContainer. See that file's header.
    // Docs: https://wearables.developer.meta.com/docs/build-integration-android/
    // ------------------------------------------------------------------------------------------

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
    testImplementation(libs.robolectric)
}

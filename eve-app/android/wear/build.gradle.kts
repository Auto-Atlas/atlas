import java.util.Properties

// :wear — the Atlas Wear OS companion app. Ships under the SAME package (applicationId app.atlas) and
// Play listing as the phone app, but is its own APK targeting watches. The watch never talks HTTP;
// the phone is the gateway. This module's job (this increment) is a REAL phone-link status screen
// driven by the Data Layer NodeClient — no fake states.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ---- Play upload signing -------------------------------------------------------------------------
// Same conditional scheme as :app — reads eve.upload.* from local.properties (gitignored) or the
// EVE_UPLOAD_* env vars (CI fallback). Missing credentials => UNSIGNED release + a loud warning, but
// the build still succeeds. The keystore lives OUTSIDE the repo and is never committed.
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
    logger.lifecycle("EVE :wear — release signing not configured — AAB will be unsigned (set eve.upload.* in local.properties or EVE_UPLOAD_* env vars).")
}

android {
    namespace = "app.eve.wear"
    compileSdk = 35

    defaultConfig {
        // Same package as the phone app — a Wear app is a variant under one Play listing.
        applicationId = "app.atlas"
        // 30 = Wear OS 3 — Play's floor for new watch apps since 2023 anyway, and the floor
        // health-services-client 1.0.0 (Health v2 passive HR) declares. Wear OS 2 was never a target.
        minSdk = 30
        targetSdk = 35
        // # release — Wear versioning scheme.
        // The watch APK ships in its own Play "Wear OS" release track under the SAME app.eve
        // listing, so its versionCode is INDEPENDENT of the phone APK's — Play matches the right
        // APK to the device by form factor. Bump this versionCode on every Wear upload (it need
        // not track the phone's). Keep versionName human-readable; it is display-only.
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Created only when eve.upload.* resolves — otherwise release stays unsigned (see top note).
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
            // R8 + resource shrinking ON to match :app. proguard-rules.pro keeps the shared
            // kotlinx-serialization DTOs the watch decodes off the Data Layer.
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
}

dependencies {
    // Shared DTOs (unused by this scaffold, wired now so later increments decode phone->watch
    // Data Layer payloads from the same source of truth as the phone app).
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Compose runtime (BOM-aligned) + Compose FOR WEAR (material/foundation/navigation).
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.wear.compose.material)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.navigation)
    implementation(libs.horologist.compose.layout)
    debugImplementation(libs.androidx.ui.tooling)

    // Data Layer — NodeClient.getConnectedNodes() backs the real phone-link check.
    implementation(libs.play.services.wearable)

    // Live voice (v3): the wrist streams mic PCM up + plays EVE's PCM down over ONE secure OkHttp
    // WebSocket to the owner's public voice door. Kept behind thin seams (WsVoiceClient / StreamingMic /
    // StreamingPcmPlayer) so the JVM unit gate uses fakes and never touches OkHttp or the audio engines.
    implementation(libs.okhttp)

    // Health v2: passive HEART_RATE_BPM stream (PassiveListenerService) behind HrAlertService; the
    // threshold brain is the pure, JVM-tested HrAlertPolicy (Health Services has no bpm goals).
    implementation(libs.androidx.health.services.client)
    // health-services-client drags in FULL guava (real ListenableFuture) alongside the
    // `com.google.guava:listenablefuture` stub the Tiles stack pulls. Same clash the phone hit with
    // connect-client, same canonical fix: declare the stub as provided-by guava so guava's real
    // class wins (without this, EveTileService lost ListenableFuture the day this dep landed).
    modules {
        module("com.google.guava:listenablefuture") {
            replacedBy("com.google.guava:guava", "listenablefuture is provided by full guava")
        }
    }

    // Notification bridging (Increment 5): BridgingManager/BridgingConfig so the watch excludes the
    // phone's approval-tagged notification from auto-bridging and owns the wrist approval natively.
    implementation(libs.androidx.wear.phone.interactions)

    // wear-phone-interactions drags in fragment 1.2.4, but MainActivity's ActivityResult permission
    // request needs fragment >= 1.3.0 (InvalidFragmentVersionForActivityResult lint). The Wear app has
    // no fragments — a constraint bumps the transitive version WITHOUT adding a direct dependency.
    constraints {
        implementation(libs.androidx.fragment)
    }

    // Wear surfaces (Increment 4): the Status Tile + pending-count complication. Tiles renders via
    // the ProtoLayout builders; the complication data-source ships SuspendingComplicationDataSourceService
    // and the update requester; concurrent-futures bridges the tile's suspend read into a ListenableFuture.
    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.wear.protolayout)
    implementation(libs.androidx.wear.protolayout.material)
    implementation(libs.androidx.wear.protolayout.expression)
    implementation(libs.androidx.wear.watchface.complications.data.source.ktx)
    implementation(libs.androidx.concurrent.futures.ktx)

    // JVM/Robolectric unit gate — mirrors :app's testOptions.
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
    testImplementation(libs.robolectric)

    // On-device integration test (SnapshotPipelineTest): writes the canonical approvals fixture
    // through the REAL DataClient on an emulator/watch so the whole snapshot pipeline (listener ->
    // decode -> VM -> UI -> tile -> notification) can be exercised without a paired phone. Runs via
    // :wear:connectedDebugAndroidTest only — never part of any shipping APK.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.core)
    // Real-rendering gesture check for the hold-to-approve money gate (HoldGestureDeviceTest) —
    // the compose test framework injects a realistic pointer stream the JVM/Robolectric suite
    // cannot, proving the gate inside the real scrollable screen hierarchy on a device.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}

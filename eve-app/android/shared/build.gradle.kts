// :shared — pure DTOs + JSON parsing shared by the phone (:app) and the watch (:wear). NO Android
// UI, NO compose, NO HTTP client. Just kotlinx.serialization data classes and their derived props,
// so both apps decode the exact same backend wire shapes from one source of truth.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.eve.shared"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // api(...) so :app and :wear get kotlinx-serialization-json transitively — the models expose
    // its types (JsonElement etc.) in their public API.
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

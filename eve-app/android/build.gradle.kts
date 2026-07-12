// Root build file. Plugins are declared here `apply false` and applied per-module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    // Google Services (FCM). Declared but never applied here; the app module applies it
    // CONDITIONALLY — only when google-services.json is present — so the build still works today.
    alias(libs.plugins.google.services) apply false
}

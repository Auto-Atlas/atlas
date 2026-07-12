# EVE app proguard rules.

# kotlinx.serialization — keep generated serializers and the @Serializable companions.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class app.eve.data.models.** {
    *** Companion;
}
-keepclasseswithmembers class app.eve.data.models.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.eve.data.models.**$$serializer { *; }

# Ktor + coroutines.
-keep class io.ktor.** { *; }
-keepclassmembers class io.ktor.** { volatile <fields>; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-keepclassmembernames class kotlinx.** { volatile <fields>; }

# stream-webrtc-android (org.webrtc) — JNI: native libjingle_peerconnection_so.so calls back
# into these Java classes by exact name, so they must NOT be renamed or stripped by R8.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Compose tooling is debug-only; nothing extra needed for release here.

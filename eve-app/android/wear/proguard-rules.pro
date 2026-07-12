# EVE wear app proguard/R8 rules.

# kotlinx.serialization — keep the generated serializers and @Serializable companions for the
# shared DTOs the watch decodes off the Wearable Data Layer. Same pattern as the phone app, for
# BOTH shared serializable packages: app.eve.data.models.** and app.eve.data.wear.**.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class app.eve.data.models.** {
    *** Companion;
}
-keepclasseswithmembers class app.eve.data.models.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.eve.data.models.**$$serializer { *; }

-keepclassmembers class app.eve.data.wear.** {
    *** Companion;
}
-keepclasseswithmembers class app.eve.data.wear.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.eve.data.wear.**$$serializer { *; }

-keepclassmembernames class kotlinx.** { volatile <fields>; }

# play-services-wearable and the wear tiles/protolayout/complications artifacts ship their own
# consumer proguard rules (bundled in their AARs), so no extra keeps are needed for them here.

# OkHttp (the live-voice WebSocket client). OkHttp 4.x ships its own consumer rules in the AAR, but
# it references optional Conscrypt/BouncyCastle/OpenJSSE providers and Animal Sniffer annotations that
# R8 warns about on a JVM-less Android target — silence those and keep OkHttp intact. The shared
# live-voice DTOs (app.eve.data.wear.**, incl. VoiceDoorConfig) are already kept by the rules above.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.codehaus.mojo.animal_sniffer.*
-keep class okhttp3.internal.platform.android.** { *; }

# UserGist SDK consumer rules — applied to apps that depend on this library.

# Kotlinx Serialization: keep serializers and annotations.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep SDK public API.
-keep class studio.usergist.feedback.UserGist { *; }
-keep class studio.usergist.feedback.api.** { *; }

# Keep model classes used by serialization (they rely on generated serializers).
-keep @kotlinx.serialization.Serializable class studio.usergist.feedback.** { *; }
-keepclassmembers class studio.usergist.feedback.** {
    kotlinx.serialization.KSerializer serializer(...);
    <init>(...);
    <fields>;
}

# Suppress warnings from OkHttp optional deps.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

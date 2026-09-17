# Ai-Cloud R8 rules — release minification.
#
# Most stacks (Hilt, Room, Compose, OkHttp) ship consumer rules in their AARs.
# Only reflection surfaces that R8 cannot infer need explicit keep rules.

# --- kotlinx.serialization -------------------------------------------------
# Retrofit responses are decoded through serializer() lookups that R8 cannot
# trace for DTOs accessed only via the generated companion. Keep the generated
# serializers of @Serializable classes in our packages.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class dev.repochat.**$$serializer { *; }
-keepclassmembers class dev.repochat.** {
    *** Companion;
}
-keepclasseswithmembers class dev.repochat.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Retrofit ---------------------------------------------------------------
# Generic signatures + annotations are read reflectively.
-keepattributes Signature, Exceptions
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>

# --- OkHttp / Okio ----------------------------------------------------------
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- security-crypto (Tink) -------------------------------------------------
# error-prone annotations are compile-time-only metadata on Tink classes.
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**

# --- Coroutines -------------------------------------------------------------
-dontwarn kotlinx.coroutines.debug.**

# Keep line numbers in release stack traces for readable CI-log debugging.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

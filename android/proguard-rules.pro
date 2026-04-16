# Kotlin Serialization
# https://github.com/Kotlin/kotlinx.serialization#android
-keepattributes *Annotation*, EnclosingMethod, InnerClasses, Signature
-keep,allowobfuscation,allowshrinking class kotlinx.serialization.json.** { *; }
-keepclassmembers class net.af0.where.e2ee.** {
    *** Companion;
    *** $serializer;
}
-keepclassmembers class net.af0.where.model.** {
    *** Companion;
    *** $serializer;
}

# E2EE protocol and models
# We keep these to ensure that the protocol structure remains stable and
# that serialization/deserialization works correctly across versions.
-keep class net.af0.where.e2ee.** { *; }
-keep class net.af0.where.model.** { *; }

# Libsodium KMP (com.ionspin.kotlin.crypto)
# These rules are necessary because libsodium uses JNI, and R8 might
# strip or rename classes/methods that are called from native code.
-keep class com.ionspin.kotlin.crypto.** { *; }
-keepclassmembers class com.ionspin.kotlin.crypto.** {
    native <methods>;
}

# Google Maps
-keep class com.google.android.gms.maps.** { *; }
-keep interface com.google.android.gms.maps.** { *; }

# ViewModels (keep constructors for reflection)
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

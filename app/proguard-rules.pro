# SPDX-License-Identifier: Apache-2.0

# kotlinx.serialization: serializers are looked up reflectively per class.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.dibs.**$$serializer { *; }

# SQLCipher loads its native library and JNI callbacks reflectively.
-keep class net.zetetic.database.** { *; }

# BouncyCastle registers providers reflectively; keep what Argon2 touches.
-keep class org.bouncycastle.crypto.** { *; }
-dontwarn org.bouncycastle.**

# Tink subtle primitives are constructed directly; keep names for stack traces only.
-dontwarn com.google.errorprone.annotations.**

# eddsa (net.i2p.crypto) resolves curve tables by name.
-keep class net.i2p.crypto.eddsa.** { *; }

# ML Kit rules ship with the AAR; silence its optional deps.
-dontwarn com.google.mlkit.**

# R8-generated: JVM-only classes referenced from optional paths never taken on Android.
-dontwarn sun.security.x509.X509Key

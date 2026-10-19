# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.bookmind.**$$serializer { *; }
-keepclassmembers class com.bookmind.** { *** Companion; }
-keepclasseswithmembers class com.bookmind.** { kotlinx.serialization.KSerializer serializer(...); }

# PdfBox-Android optional JPEG2000 decoder is not bundled.
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
# OkHttp platform adapters
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
# jsoup optional re2j
-dontwarn com.google.re2j.**

# LiteRT-LM (on-device model engine): the native library looks up these classes and members by name over JNI,
# and the library ships no consumer rules.
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class com.google.ai.edge.litertlm.** { native <methods>; }
-dontwarn com.google.ai.edge.litertlm.**

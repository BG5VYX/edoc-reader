# ---- 保留数据模型（Gson 反射序列化需要）----
-keep class com.edocreader.app.data.** { *; }
-keepclassmembers class com.edocreader.app.data.** { <fields>; }

# ---- Gson ----
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-dontwarn sun.misc.**

# ---- ML Kit 文本识别 ----
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text** { *; }
-dontwarn com.google.mlkit.**

# ---- CameraX ----
-dontwarn androidx.camera.**

# ---- Kotlin ----
-dontwarn kotlin.**
-keepclassmembers class kotlin.Metadata { public <methods>; }

# ---- 保留枚举 ----
-keepclassmembers enum * { *; }

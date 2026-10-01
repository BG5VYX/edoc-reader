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

# ---- JPEG 2000 解码器（内嵌的 jj2000）----
# 证件照片可能是 JPEG 2000（Android 系统不内置该解码器），这里内嵌了 jj2000。
# 它依赖完整的类层次（反射与子类实例化较多），整体保留，不做裁剪与改名。
-keep class ucar.jpeg.** { *; }
-dontwarn ucar.jpeg.**
-keep class com.edocreader.app.jp2.** { *; }

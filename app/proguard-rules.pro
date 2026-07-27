# 保留 Termux bridge 接口（反射调用 native helper）
-keep class com.mcserver.manager.runtime.** { *; }
-keep class com.mcserver.manager.service.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Compose
-keep class androidx.compose.** { *; }

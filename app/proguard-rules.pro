# ProGuard rules for Unpackr release optimization

# Moshi JSON Serialization
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepclassmembers class * {
    @com.squareup.moshi.Json *;
}
-keep class com.unpackr.engine.** { 
    *; 
}

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Kotlin Coroutines
-keep class kotlinx.coroutines.** { *; }

# Preserve Line Numbers for Crash Stack Traces
-keepattributes SourceFile,LineNumberTable

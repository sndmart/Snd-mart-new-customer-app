# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep all data model classes used for JSON (Moshi)
-keep class com.example.data.model.** { *; }
-keepclassmembers class com.example.data.model.** { *; }

# Moshi
-keep class com.squareup.moshi.** { *; }
-keep @com.squareup.moshi.JsonClass class * { *; }
-keepclassmembers class * { @com.squareup.moshi.Json <fields>; }
-keepclassmembers class **JsonAdapter { *; }

# Kotlin reflection for Moshi reflection (KotlinJsonAdapterFactory)
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.jvm.internal.** { *; }
-dontwarn kotlin.reflect.jvm.internal.**
-keepclassmembers class com.example.data.** { <init>(...); <fields>; }

# Retrofit
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keepclasseswithmembers class * { @retrofit2.http.* <methods>; }
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**

# Firebase Messaging service classes referenced from the manifest are kept automatically.
# Keep the raw sound resource used for alerts
-keep class **.R$raw { *; }

# Razorpay Checkout
-keep class com.razorpay.** { *; }
-dontwarn com.razorpay.**

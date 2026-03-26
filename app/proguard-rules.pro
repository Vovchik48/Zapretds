# Keep our classes
-keep class com.zapret.android.** { *; }
-keepclassmembers class com.zapret.android.** { *; }

# Keep Kotlin stuff
-keep class kotlin.** { *; }
-keepclassmembers class kotlin.** { *; }

# Keep coroutines
-keep class kotlinx.coroutines.** { *; }
-keepclassmembers class kotlinx.coroutines.** { *; }

# Don't warn about missing classes
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlin.**

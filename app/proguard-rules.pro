# ═══════════════════════════════════════════════════════════════════
# DualSpace OBS - ProGuard Rules
# Production-ready rules for all application dependencies
# ═══════════════════════════════════════════════════════════════════

# ═══════════════════════════════════════════════════════════════════
# General Android Defaults
# ═══════════════════════════════════════════════════════════════════
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations

# Prevent stripping of enum methods
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ═══════════════════════════════════════════════════════════════════
# Keep Application & Model Classes
# ═══════════════════════════════════════════════════════════════════
-keep public class com.dualspace.obs.** {
    public protected *;
}
-keepclassmembers class com.dualspace.obs.** {
    public protected *;
}

# ═══════════════════════════════════════════════════════════════════
# AndroidX AppCompat
# ═══════════════════════════════════════════════════════════════════
-keep public class androidx.appcompat.widget.** { *; }
-keep public class androidx.appcompat.app.** { *; }
-dontwarn androidx.appcompat.resources.**

# ═══════════════════════════════════════════════════════════════════
# Material Design Components
# ═══════════════════════════════════════════════════════════════════
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# ═══════════════════════════════════════════════════════════════════
# AndroidX Lifecycle (ViewModel + LiveData)
# ═══════════════════════════════════════════════════════════════════
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keep class * extends androidx.lifecycle.AndroidViewModel {
    <init>(android.app.Application);
}
-keepclassmembers class * extends androidx.lifecycle.AndroidViewModel {
    <init>(android.app.Application);
}
-keep class androidx.lifecycle.** { *; }
-dontwarn androidx.lifecycle.**

# ═══════════════════════════════════════════════════════════════════
# AndroidX Navigation Component
# ═══════════════════════════════════════════════════════════════════
-keepnames class androidx.navigation.fragment.** { *; }
-keep class androidx.navigation.** { *; }
-dontwarn androidx.navigation.**

# Keep NavArgs generated classes
-keepclassmembers class * {
    ** Args$*;
}

# ═══════════════════════════════════════════════════════════════════
# Room Database
# ═══════════════════════════════════════════════════════════════════
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
-keep @androidx.room.Entity class *
-dontwarn androidx.room.**

# Keep database migration classes
-keep class * extends androidx.room.migration.Migration { *; }

# ═══════════════════════════════════════════════════════════════════
# WorkManager
# ═══════════════════════════════════════════════════════════════════
-keep class * extends androidx.work.Worker {
    public <init>(android.content.Context,androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.CoroutineWorker {
    public <init>(android.content.Context,androidx.work.WorkerParameters);
}
-keepclassmembers class androidx.work.** { *; }
-dontwarn androidx.work.**

# ═══════════════════════════════════════════════════════════════════
# CameraX
# ═══════════════════════════════════════════════════════════════════
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ═══════════════════════════════════════════════════════════════════
# Media3 ExoPlayer
# ═══════════════════════════════════════════════════════════════════
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Keep Media3 metadata keys
-keepclassmembers class androidx.media3.common.** {
    *;
}

# ═══════════════════════════════════════════════════════════════════
# Gson
# ═══════════════════════════════════════════════════════════════════
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Keep application model classes serialized with Gson
-keep class com.dualspace.obs.model.** { *; }
-keep class com.dualspace.obs.data.model.** { *; }
-keep class com.dualspace.obs.data.local.entity.** { *; }
-keep class com.dualspace.obs.network.** { *; }

# Keep fields with @SerializedName
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ═══════════════════════════════════════════════════════════════════
# OkHttp3
# ═══════════════════════════════════════════════════════════════════
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }

# ═══════════════════════════════════════════════════════════════════
# EventBus
# ═══════════════════════════════════════════════════════════════════
-keepattributes *Annotation*
-keepclassmembers class * {
    @org.greenrobot.eventbus.Subscribe <methods>;
}
-keep enum org.greenrobot.eventbus.ThreadMode { *; }
-keep class ** extends org.greenrobot.eventbus.util.ThrowableFailureEvent { <init>(java.lang.Throwable); }

# Only keep EventBus index for classes that use @Subscribe
-keepclassmembers class * {
    @org.greenrobot.eventbus.Subscribe *;
}
-keep class org.greenrobot.eventbus.** { *; }

# ═══════════════════════════════════════════════════════════════════
# Lottie Animations
# ═══════════════════════════════════════════════════════════════════
-keep class com.airbnb.lottie.** { *; }
-dontwarn com.airbnb.lottie.**

# ═══════════════════════════════════════════════════════════════════
# Glide Image Loading
# ═══════════════════════════════════════════════════════════════════
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
    <init>(...);
}
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-keep class com.bumptech.glide.load.data.ParcelFileDescriptorRewinder$InternalRewinder { *** rewind(); }
-keep class com.bumptech.glide.** { *; }
-dontwarn com.bumptech.glide.**

# ═══════════════════════════════════════════════════════════════════
# MPAndroidChart
# ═══════════════════════════════════════════════════════════════════
-keep class com.github.mikephil.charting.** { *; }
-dontwarn com.github.mikephil.charting.**

# ═══════════════════════════════════════════════════════════════════
# PermissionX
# ═══════════════════════════════════════════════════════════════════
-keep class com.permissionx.guolindev.** { *; }
-dontwarn com.permissionx.guolindev.**

# ═══════════════════════════════════════════════════════════════════
# Retrofit (if added later) - Pre-emptive
# ═══════════════════════════════════════════════════════════════════
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# ═══════════════════════════════════════════════════════════════════
# Kotlin (if Kotlin modules are added later) - Pre-emptive
# ═══════════════════════════════════════════════════════════════════
-dontwarn kotlin.**
-dontwarn kotlin.reflect.jvm.internal.**
-keep class kotlin.Metadata { *; }

# ═══════════════════════════════════════════════════════════════════
# JNI / Native Methods
# ═══════════════════════════════════════════════════════════════════
-keepclasseswithmembernames class * {
    native <methods>;
}

# ═══════════════════════════════════════════════════════════════════
# Parcelable & Serializable
# ═══════════════════════════════════════════════════════════════════
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ═══════════════════════════════════════════════════════════════════
# R8 Optimization Hints
# ═══════════════════════════════════════════════════════════════════
# Remove logging in release builds for performance
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Suppress warnings for common third-party libraries
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn okhttp3.internal.platform.**

# ═══════════════════════════════════════════════════════════════════
# WebView JavaScript Interface
# ═══════════════════════════════════════════════════════════════════
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ═══════════════════════════════════════════════════════════════════
# Custom Views (keep constructors for layout inflation)
# ═══════════════════════════════════════════════════════════════════
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public <init>(android.content.Context, android.util.AttributeSet, int, int);
    public void set*(***);
}

# Keep custom preference classes
-keep public class * extends androidx.preference.Preference {
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

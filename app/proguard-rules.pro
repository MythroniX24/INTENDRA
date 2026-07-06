# ============================================================
#  INTERNDRA — ProGuard / R8 rules  v2.1.0
#  Applied only in release builds (isMinifyEnabled = true)
# ============================================================

# ── Keep application entry points ────────────────────────────────────────
-keep class com.interndra.InterndraApplication { *; }
-keep class com.interndra.MainActivity { *; }

# ── Room ─────────────────────────────────────────────────────────────────
# Entities, DAOs and Database must survive shrinking
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao    class * { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
# Prevent renaming of column names used in SQL string literals
-keepclassmembers @androidx.room.Entity class * {
    @androidx.room.ColumnInfo <fields>;
    @androidx.room.PrimaryKey <fields>;
}

# ── DataStore ────────────────────────────────────────────────────────────
-keep class androidx.datastore.** { *; }

# ── Kotlin coroutines & serialisation ────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-keep class kotlin.Metadata { *; }

# ── Gson (JSON serialisation / deserialisation) ───────────────────────────
# Keep all model classes that Gson touches via reflection
-keep class com.interndra.data.model.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keep class sun.misc.Unsafe { *; }
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory  { *; }
-keep class * implements com.google.gson.JsonSerializer       { *; }
-keep class * implements com.google.gson.JsonDeserializer     { *; }

# ── OkHttp / Okio ────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-keep class okhttp3.** { *; }

# ── Jsoup (HTML parsing for web search) ──────────────────────────────────
-keep class org.jsoup.** { *; }
-keeppackagenames org.jsoup.parser
-keeppackagenames org.jsoup.nodes

# ── Markwon (Markdown rendering) ─────────────────────────────────────────
-keep class io.noties.markwon.** { *; }
-keep interface io.noties.markwon.** { *; }

# ── WorkManager ──────────────────────────────────────────────────────────
-keep class * extends androidx.work.Worker        { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }
-keepclassmembers class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ── Accessibility & Notification services ────────────────────────────────
-keep class com.interndra.service.AgentAccessibilityService { *; }
-keep class com.interndra.services.InterndraNotificationListener { *; }
-keep class com.interndra.services.AutomationWorker { *; }

# ── llama.cpp JNI bridge (native methods must not be renamed) ─────────────
-keepclasseswithmembernames class com.interndra.ai.LocalAiEngine {
    native <methods>;
}
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── ViewModels (must survive reflection-based construction) ───────────────
-keep class com.interndra.ui.viewmodel.** { *; }

# ── Compose (R8 handles most of this, but keep extension lambdas) ─────────
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ── Suppress common benign warnings ──────────────────────────────────────
-dontwarn java.lang.instrument.ClassFileTransformer
-dontwarn sun.misc.SignalHandler
-dontwarn java.lang.invoke.StringConcatFactory
-dontwarn org.slf4j.**

# ── Debugging: keep source file names in stack traces ────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile



-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

-dontobfuscate

-keep class kotlin.** { *; }
-dontwarn kotlin.**

-keep class kotlinx.** { *; }

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

-keep class kotlinx.serialization.** { *; }

-keep class ani.sanin.** { *; }
-keep class ani.sanin.download.DownloadsManager { *; }

-keep class eu.kanade.** { *; }
-keep class uy.kohesive.injekt.** { *; }

-keepclassmembers class uy.kohesive.injekt.api.FullTypeReference {
    <init>(...);
}

-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**
-keep class org.nanohttpd.** { *; }
-dontwarn org.nanohttpd.**

-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

-keep class okhttp3.** { *; }
-dontwarn okhttp3.**

-keep class okio.** { *; }
-dontwarn okio.**

-keep class androidx.core.content.res.ResourcesCompat { *; }
-keepclassmembers,allowoptimization class androidx.core.content.res.ResourcesCompat {
    public static ** getDrawable(...);
    public static ** getColor(...);
    public static ** getColorStateList(...);
}
-keepclassmembers,allowoptimization class androidx.core.content.ContextCompat {
    public static ** getDrawable(...);
    public static ** getColor(...);
}
-keepclassmembers,allowoptimization class androidx.core.content.res.ColorStateListInflaterCompat { *; }
-dontwarn androidx.core.content.res.ResourcesCompat

-keep class androidx.activity.** { *; }
-dontwarn androidx.activity.**
-keep class androidx.appcompat.** { *; }
-dontwarn androidx.appcompat.**
-keep class androidx.fragment.** { *; }
-dontwarn androidx.fragment.**
-keep class androidx.savedstate.** { *; }
-dontwarn androidx.savedstate.**
-keep class androidx.lifecycle.** { *; }
-dontwarn androidx.lifecycle.**
-keep class com.google.android.material.bottomsheet.** { *; }
-keep class com.google.android.material.dialog.** { *; }
-dontwarn com.google.android.material.**

-keep class androidx.preference.** { *; }

-keep class androidx.work.impl.WorkDatabase_Impl { *; }

-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }

-keep class org.jsoup.** { *; }
-keepclassmembers class org.jsoup.nodes.Document { *; }

-keep,allowoptimization class app.cash.quickjs.** { public protected *; }

-keep class rx.internal.util.unsafe.** { *; }

-dontwarn sun.misc.Unsafe
-dontwarn org.graalvm.nativeimage.**
-dontwarn com.oracle.svm.core.annotate.**

-keep class rx.internal.util.unsafe.** { *; }

-keepclassmembers class rx.internal.util.unsafe.** {
    long producerIndex;
    long consumerIndex;
}

-keep class rx.internal.** { *; }

-dontwarn sun.misc.Unsafe

-keep class com.github.aachartmodel.** { *; }
-dontwarn com.github.aachartmodel.**

-keep class com.lagradost.** { *; }
-dontwarn com.lagradost.**

-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**

-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**

-keep class dev.whyoleg.cryptography.** { *; }
-dontwarn dev.whyoleg.cryptography.**

-keep class com.fleeksoft.ksoup.** { *; }
-dontwarn com.fleeksoft.ksoup.**

-keep class org.schabi.newpipe.** { *; }
-dontwarn org.schabi.newpipe.**

-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

-keep class kotlinx.datetime.** { *; }
-dontwarn kotlinx.datetime.**

-keep class kotlinx.io.** { *; }
-dontwarn kotlinx.io.**

-keep class kotlinx.atomicfu.** { *; }
-dontwarn kotlinx.atomicfu.**

-keep class org.json.** { *; }
-dontwarn org.json.**

-keep class androidx.appcompat.app.AlertDialog { *; }
-keep class androidx.appcompat.app.AlertDialog$Builder { *; }
-keep class androidx.appcompat.app.AlertDialog$Builder$* { *; }
-dontwarn androidx.appcompat.app.AlertDialog

-dontwarn jdk.internal.misc.Unsafe
-dontwarn jdk.internal.misc.**

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

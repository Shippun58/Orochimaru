plugins {
    alias(libs.plugins.android)
    alias(libs.plugins.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose)
}

if (gradle.startParameter.taskNames.any { it.contains("google", true) }) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

val gitCommitHash = providers.exec {
    commandLine("git", "rev-parse", "--verify", "--short", "HEAD")
}.standardOutput.asText.get().trim()

android {
    namespace = "ani.sanin"
    compileSdk = 37

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("release.keystore")
            storePassword = "sanin123"
            keyAlias = "sanin"
            keyPassword = "sanin123"
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
        }
    }

    defaultConfig {
        applicationId = "ani.sanin"
        minSdk = 24
        targetSdk = 36

        versionName = "3.2.2"
        versionCode = (versionName ?: "1.0.0").split(".")

            .map { it.toInt() * 100 }
            .joinToString("")
            .toInt()

        signingConfig = signingConfigs.getByName("debug")

        buildConfigField("String", "SIMKL_CLIENT_ID", "\"\"")
        buildConfigField("String", "SIMKL_CLIENT_SECRET", "\"\"")
        buildConfigField("long", "BUILD_DATE", "System.currentTimeMillis()")
        buildConfigField("String", "MAL_KEY", "\"\"")
    }

    flavorDimensions += listOf("store", "device")

    productFlavors {
        create("fdroid") {
            dimension = "store"
            versionNameSuffix = "-fdroid"
        }
        create("google") {
            dimension = "store"
            isDefault = true
        }

        create("phone") {
            dimension = "device"
            isDefault = true
            minSdk = 24
        }
        create("tv") {
            dimension = "device"
            minSdk = 24
        }
    }

    buildTypes {
        create("alpha") {
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-alpha01-$gitCommitHash"
            isDebuggable = true
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            isDefault = true
        }

        getByName("debug") {
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta01"
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {

            pickFirsts += listOf("lib/**/libc++_shared.so")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.addAll(
            "-XXLanguage:+ContextParameters",
            "-Xmulti-platform",
            "-opt-in=com.lagradost.cloudstream3.InternalAPI",
            "-opt-in=com.lagradost.cloudstream3.Prerelease",
            "-opt-in=kotlin.uuid.ExperimentalUuidApi"
        )
    }
}

dependencies {

    add("googleImplementation", platform(libs.firebase.bom))
    add("googleImplementation", libs.bundles.firebase)

    implementation(libs.bundles.androidx)

    implementation(libs.kotlin.reflect)
    implementation(libs.kotlin.stdlib)

    implementation(libs.bundles.misc)

    implementation(libs.bundles.glide)
    ksp(libs.glide.ksp)

    implementation(libs.bundles.media3)
    implementation(libs.previewseekbar.media3)
    implementation(libs.bundles.subtitles)
    implementation(libs.vad.webrtc)

    implementation(libs.material)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.compose.runtime)
    implementation(libs.compose.activity)
    implementation(libs.flexbox)
    implementation(libs.kenburns)
    implementation(libs.subsampling)
    implementation(libs.lottie)
    implementation(libs.shimmer)
    implementation(files("libs/AnimatedBottomBar-7fcb9af.aar"))
    implementation(libs.qrcode.kotlin)
    implementation(libs.gesture)

    implementation(libs.bundles.markwon)
    implementation(libs.bundles.rx)
    implementation(libs.bundles.groupie)
    implementation(libs.charts)
    implementation(libs.dialogs)
    implementation(libs.fuzzy)
    implementation(libs.overlappingpanels)
    implementation(libs.paging)
    implementation(libs.bundles.okhttp)
    implementation(libs.okio)

    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlinx.atomicfu)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.io.core)
    implementation(libs.ksoup)
    implementation(libs.ktor.http)
    implementation(libs.cryptography.core)
    implementation(libs.cryptography.provider.optimal)
    implementation(libs.newpipeextractor)
    implementation(libs.rhino)
    implementation(libs.androidsvg.aar)

    implementation(libs.libarchive)
    implementation(libs.xmlutil.core)
    implementation(libs.xmlutil.serialization)

    implementation(libs.coil3)
    implementation(libs.coil.network.okhttp)
    implementation(libs.media3.common)
    implementation(libs.media3.container)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.navigation.fragment.ktx)
    implementation(libs.navigation.ui.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.colorpicker)
    implementation(libs.constraintlayout)
    implementation(libs.core.ktx)
    implementation(libs.work.runtime.ktx)

    implementation(libs.safefile)
    implementation(libs.nicehttp)
    implementation(libs.jsoup)
    implementation(libs.juniversalchardet)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.nanohttpd)
    implementation(libs.palette.ktx)
    implementation(libs.preference.ktx)
    implementation(libs.activity.ktx)
    implementation(libs.annotation)
    implementation(libs.appcompat)
    implementation(libs.fragment.ktx)
    implementation(libs.json)
    implementation(libs.tvprovider)

    add("phoneImplementation", "com.google.errorprone:error_prone_annotations:2.48.0")
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)
}

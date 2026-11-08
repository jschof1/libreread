import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import com.google.protobuf.gradle.id

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.androidx.navigation.safeargs)
    alias(libs.plugins.baselineprofile)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.protobuf)
}

/*
'keystore.properties' should look like the following:

storeFile=my.keystore
storePassword=my_store_password
keyAlias=my_key_alias
keyPassword=my_key_password
 */

/**
 * Encode a build environment value as a Java string literal for BuildConfig.
 * Backslashes, quotes and control characters are escaped so that no value can break out of
 * the literal. Control characters use octal escapes because Java processes unicode escapes
 * before lexing string literals.
 */
fun String.asJavaStringLiteral(): String {
    val out = StringBuilder("\"")
    for (ch in this) {
        when {
            ch == '\\' -> out.append("\\\\")
            ch == '"' -> out.append("\\\"")
            ch == '\n' -> out.append("\\n")
            ch == '\r' -> out.append("\\r")
            ch == '\t' -> out.append("\\t")
            ch.code < 0x20 || ch.code == 0x7f -> out.append("\\").append(String.format("%03o", ch.code))
            else -> out.append(ch)
        }
    }
    return out.append('"').toString()
}

// LibreRead article service configuration, injected at build time. Never commit real values.
val libreReadApiUrl: String = providers.environmentVariable("LIBREREAD_API_URL").orNull
    ?.trim() ?: ""
val libreReadDeviceToken: String = providers.environmentVariable("LIBREREAD_DEVICE_TOKEN").orNull
    ?.trim() ?: ""

val libreReadTestBuildType: String = providers.gradleProperty("libreread.testBuildType").orNull
    ?.trim()?.takeIf { it.isNotEmpty() } ?: "debug"
require(libreReadTestBuildType == "debug" || libreReadTestBuildType == "release") {
    "libreread.testBuildType must be debug or release"
}

val libreReadKeystorePath: String? = providers.environmentVariable("LIBREREAD_KEYSTORE_PATH").orNull
    ?.trim()?.takeIf { it.isNotEmpty() }
val libreReadKeystorePassword: String = providers.environmentVariable("LIBREREAD_KEYSTORE_PASSWORD").orNull
    ?.takeIf { it.isNotEmpty() } ?: "android"
val libreReadKeyAlias: String = providers.environmentVariable("LIBREREAD_KEY_ALIAS").orNull
    ?.takeIf { it.isNotEmpty() } ?: "androiddebugkey"
val libreReadKeyPassword: String = providers.environmentVariable("LIBREREAD_KEY_PASSWORD").orNull
    ?.takeIf { it.isNotEmpty() } ?: libreReadKeystorePassword

val keystoreProperties = Properties()
val keystoreFileExists = rootProject.file("keystore.properties").exists();
if (keystoreFileExists) {
    keystoreProperties.load(rootProject.file("keystore.properties").inputStream())
}

android {
    compileSdk = 36

    defaultConfig {
        applicationId = "net.aspectstudio.libreread"
        minSdk = 26
        targetSdk = 36
        versionCode = 73
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resValue("string", "app_name", "LibreRead")
        buildConfigField("String", "LIBREREAD_API_URL", libreReadApiUrl.asJavaStringLiteral())
        buildConfigField("String", "LIBREREAD_DEVICE_TOKEN", libreReadDeviceToken.asJavaStringLiteral())
    }

    testBuildType = libreReadTestBuildType

    testOptions {
        // android.* stubs return defaults in JVM tests (androidx AtomicFile logs on error paths)
        unitTests.isReturnDefaultValues = true
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("exportSchema", "true")
    }

    viewBinding {
        enable = true
    }

    signingConfigs {
        libreReadKeystorePath?.let { personalKey ->
            getByName("debug") {
                storeFile = file(personalKey)
                storePassword = libreReadKeystorePassword
                keyAlias = libreReadKeyAlias
                keyPassword = libreReadKeyPassword
            }
        }
        if (keystoreFileExists) {
            create("release") {
                storeFile = keystoreProperties["storeFile"]?.let { file(it as String) }
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        } else libreReadKeystorePath?.let { personalKey ->
            create("release") {
                storeFile = file(personalKey)
                storePassword = libreReadKeystorePassword
                keyAlias = libreReadKeyAlias
                keyPassword = libreReadKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")?.takeIf { it.storeFile != null }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (libreReadTestBuildType == "release") {
                proguardFile("proguard-instrumentation.pro")
            }
        }

        getByName("debug") {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "LibreRead Debug")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
            javaParameters = true
        }
    }

    packaging {
        jniLibs.excludes.add("lib/armeabi-v7a/*_neon.so")
    }

    tasks.register("testClasses")

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    buildFeatures {
        buildConfig = true
        resValues = true
    }

    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

    // language preference for Android 13 and above
    androidResources {
        generateLocaleConfig = true
    }

    namespace = "com.github.libretube"
}

dependencies {
    /* Android Core */
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.collection)
    implementation(libs.androidx.media)
    implementation(libs.androidx.swiperefreshlayout)

    /* Android Lifecycle */
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.livedata)
    implementation(libs.lifecycle.service)

    /* Design */
    implementation(libs.material)

    /* ExoPlayer */
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.session)

    /* Retrofit and Kotlinx Serialization */
    implementation(libs.square.retrofit)
    implementation(libs.logging.interceptor)
    implementation(libs.kotlinx.serialization)
    implementation(libs.kotlinx.datetime)
    implementation(libs.converter.kotlinx.serialization)
    implementation(libs.google.protobuf.javalite)
    implementation(libs.google.protobuf.kotlin.lite)

    /* NewPipe Extractor */
    implementation(libs.newpipeextractor)


    /* Coil */
    coreLibraryDesugaring(libs.desugaring)
    implementation(libs.coil)
    implementation(libs.coil.network.okhttp)

    /* Room */
    ksp(libs.room.compiler)
    implementation(libs.room)

    /* Baseline profile generation */
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    /* AndroidX Paging */
    implementation(libs.androidx.paging)

    /* Testing */
    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espressoCore)
}

//TODO: exclude from release protobuf
protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                //TODO: only generate kotlin code
                id("java") {
                    option("lite")
                }
//                id("kotlin") {
//                    option("lite")
//                }
            }
        }
    }
}

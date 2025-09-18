// app/build.gradle.kts
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.kotlin.kapt")          // Glide compiler
    alias(libs.plugins.google.services)      // Google Services
}

android {
    namespace = "com.hag.al_quran"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hag.al_quran"
        minSdk = 24
        targetSdk = 35
        versionCode = 35
        versionName = "1.0.35"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions { jvmTarget = "11" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions { kotlinCompilerExtensionVersion = "2.0.1" }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // ✅ لا تقسيم للغات في App Bundle
    bundle { language { enableSplit = false } }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(libs.play.app.update)
    implementation(libs.play.app.update.ktx)
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation ("androidx.core:core-splashscreen:1.0.1")

    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.recyclerview)

    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("io.github.chrisbanes:photoview:2.3.0")

    // Glide
    implementation("com.github.bumptech.glide:glide:4.16.0")
    kapt("com.github.bumptech.glide:compiler:4.16.0")

    // تكامل Glide مع OkHttp (كاش شبكة)
    implementation("com.github.bumptech.glide:okhttp3-integration:4.16.0") {
        exclude(group = "glide-parent")
    }

    // QR
    implementation("com.github.kenglxn.QRGen:android:2.6.0")

    // ExoPlayer
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")

    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Firebase via BOM (Analytics + Remote Config فقط)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.config.ktx)

    // ✅ Analytics مع استبعادات تمنع سحب AD_ID و AdServices
    implementation(libs.firebase.analytics.ktx) {
        // يوقف سحب play-services-ads-identifier القديم
        exclude(group = "com.google.android.gms", module = "play-services-ads-identifier")
        // احترازي: لا تسحب play-services-ads
        exclude(group = "com.google.android.gms", module = "play-services-ads")
        // يوقف سحب مكتبة AdServices (ACCESS_ADSERVICES_AD_ID)
        exclude(group = "androidx.privacysandbox.ads", module = "ads-adservices")
        exclude(group = "androidx.ads", module = "ads-adservices")
        exclude(group = "com.google.android.adservices", module = "adservices")
    }

    // Palette
    implementation("androidx.palette:palette-ktx:1.0.0")

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

kapt { correctErrorTypes = true }

/**
 * ✅ استبعادات عامة لأي تبعية قد تحاول سحب معرّفات الإعلانات.
 * تُطبق على جميع الـ configurations.
 */
configurations.all {
    exclude(group = "com.google.android.gms", module = "play-services-ads-identifier")
    exclude(group = "com.google.android.gms", module = "play-services-ads")
    exclude(group = "androidx.privacysandbox.ads", module = "ads-adservices")
    exclude(group = "androidx.ads", module = "ads-adservices")
    exclude(group = "com.google.android.adservices", module = "adservices")
}

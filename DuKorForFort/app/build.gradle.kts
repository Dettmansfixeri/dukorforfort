plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "se.dettmansfixeri.dukorforfort"
    compileSdk = 35

    defaultConfig {
        applicationId = "se.dettmansfixeri.dukorforfort"
        minSdk = 26
        targetSdk = 35
        // På GitHub sätts BUILD_NUMBER (körningens nummer) → t.ex. 1.1.5.
        // Lokala byggen blir "1.1-lokal" med lågt versionCode.
        val build = System.getenv("BUILD_NUMBER")?.toIntOrNull()
        versionCode = if (build != null) 100 + build else 2
        versionName = "1.1" + (if (build != null) ".$build" else "-lokal")    }

    signingConfigs {
        // På GitHub pekar DEBUG_KEYSTORE på samma nyckel som används lokalt,
        // så att appen kan uppdateras utan att avinstalleras.
        getByName("debug") {
            System.getenv("DEBUG_KEYSTORE")?.let { storeFile = file(it) }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Rörelseavkänning (Activity Recognition) för automatisk start
    implementation("com.google.android.gms:play-services-location:21.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // org.json finns inte i JVM-tester
}

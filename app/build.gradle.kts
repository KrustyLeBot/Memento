plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI sets GITHUB_RUN_NUMBER; local builds fall back to 1.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "app.memento"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.memento"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    signingConfigs {
        create("shared") {
            // The keystore is decoded by the CI from a GitHub secret; never committed.
            val path = System.getenv("KEYSTORE_PATH")
            if (path != null) {
                storeFile = file(path)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Local builds (no KEYSTORE_PATH) keep Android's default debug key.
            if (System.getenv("KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("shared")
            }
        }
        // What the CI ships: not debuggable, signed with the same key as before so it updates in place.
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (System.getenv("KEYSTORE_PATH") != null) "shared" else "debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // the shared map page (web/map/) is served from the APK at assets/map/
    sourceSets["main"].assets.srcDirs("src/main/assets", "../web")

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric renders the screenshot tests (ui/screenshots) with the real resources.
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // the default 512 MB ran out on CI once the long-track tests joined the tile-plan ones in the same JVM
            it.maxHeapSize = "2g"
            // ScreenshotAudit only runs on demand: ./gradlew testDebugUnitTest -PuiAudit=<output dir>
            val audit = project.findProperty("uiAudit")?.toString()
            if (audit == null) it.exclude("**/screenshots/**")
            else {
                it.systemProperty("uiAudit", audit)
                it.systemProperty("roborazzi.test.record", "true")
                it.maxHeapSize = "3g"
                // Robolectric downloads its Android jars itself; -ProbolectricRepo points it at a Maven Central mirror.
                project.findProperty("robolectricRepo")?.let { url -> it.systemProperty("robolectric.dependency.repo.url", url) }
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-text")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // Document scanner (camera, page corners, perspective) provided by Google Play services.
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // ICS (iCalendar) read/write and RRULE iteration for the agenda; plain Java. Jackson only serves jCal/JSON, unused.
    implementation("net.sf.biweekly:biweekly:0.6.8") {
        exclude(group = "com.fasterxml.jackson.core")
    }

    testImplementation("junit:junit:4.13.2")
    // Screenshot audit of the Compose screens on the JVM (ScreenshotAudit); test-only, not in the APK.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.43.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.43.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
}

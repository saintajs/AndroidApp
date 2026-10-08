plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "com.nexti.minisoporte"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.nexti.minisoporte"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0-internal"
        // CA-08 / BR-042: mock wiring is the DEFAULT — the injection-point task
        // flips this to real-network wiring via BuildConfig.USE_MOCK_BACKEND.
        buildConfigField("boolean", "USE_MOCK_BACKEND", "true")
        // Instrumented (androidTest) tier runs JUnit4 tests on device/emulator —
        // the authoritative verification of the platform secure-storage-backed
        // TokenStore (testing.yaml harness; KeystoreTokenStoreInstrumentedTest).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // CA-07 / CONF-004: emulator loopback to the local FastAPI.
            // Cleartext is acceptable in debug only (platform default; no
            // usesCleartextTraffic override is set anywhere).
            buildConfigField("String", "BASE_URL", "\"http://10.0.2.2:8000\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // CA-07 / CONF-004 / BR-028: the release base URL comes from a Gradle
            // property (override with -Pminisoporte.releaseBaseUrl=...); the
            // fallback is HTTPS and never a secret. Cleartext is never allowed in
            // release — usesCleartextTraffic stays at its secure default (false).
            val releaseHost =
                (project.findProperty("minisoporte.releaseBaseUrl") as String?)
                    ?: "https://api.minisoporte.internal-test"
            buildConfigField("String", "BASE_URL", "\"$releaseHost\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // US-012: BuildConfig carries the API base URL for the network layer.
        buildConfig = true
    }

    lint {
        // CA-02 / BR-031 gates: these three checks fail the build at ERROR severity.
        abortOnError = true
        error += setOf("ContentDescription", "UnusedResources", "HardcodedText")
    }

    testOptions {
        // isReturnDefaultValues: plain JVM unit tests (US-005) get android.* stub defaults.
        // isIncludeAndroidResources: Robolectric + Compose UI tests need merged resources on the JVM.
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// ktlint (standard ruleset) gates `check`: the plugin wires ktlintCheck into check
// automatically once applied. Official code style matches kotlin.code.style=official.
// ktlint CLI pinned explicitly: >=1.3 knows @Composable functions (function-naming).
ktlint {
    android.set(true)
    version.set("1.5.0")
}

// OkHttp 5.x is a Kotlin multiplatform artifact: on an Android consumer Gradle
// selects the `okhttp-android` variant, whose AAR metadata demands compileSdk 37 —
// above this project's pinned compileSdk 36 (openspec/config.yaml stack). The
// `okhttp-jvm` variant is the identical 5.5.0 code without that metadata, so
// resolve OkHttp 5.5.0 through the JVM variant everywhere. The version catalog
// pin (okhttp = "5.5.0") stays the single source of the version.
configurations.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("com.squareup.okhttp3:okhttp"))
            .using(module("com.squareup.okhttp3:okhttp-jvm:${libs.versions.okhttp.get()}"))
            .because("okhttp-android 5.5.0 AAR metadata requires compileSdk 37; the project is pinned to compileSdk 36")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Network stack (US-012 / CA-02): Retrofit 3.0.0 + OkHttp 5.5.0 +
    // kotlinx-serialization-json 1.11.0 (pins live in the version catalog).
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.serialization.json)

    // Compose (versions decided by the BOM)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)

    // Unit tests: plain JVM (US-005) + Robolectric/Compose component tests on the JVM
    testImplementation(libs.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    // US-012: MockWebServer 5.5.0 for the network-layer test tier
    testImplementation(libs.okhttp.mockwebserver)

    // Compose UI test (BOM-managed); component tests land with the screen stories
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Instrumented tests (androidTest): the authoritative platform secure-storage
    // verification runs on a real device/emulator, where the platform key provider
    // is fully available (testing.yaml). Minimal classpath: JUnit4 + the AndroidX
    // test runner; coroutines-core already flows in from the main implementation deps.
    androidTestImplementation(libs.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)

    // testReleaseUnitTest: Robolectric parses the activity table from the binary
    // manifest inside apk-for-local-test.ap_, which is packaged from the VARIANT
    // runtime classpath. createComposeRule() launches androidx.activity.ComponentActivity
    // (declared by ui-test-manifest) — with the AAR on debugImplementation only, the
    // release ap_ had no such activity and every Compose test failed with
    // "Unable to resolve activity" (Robolectric PR 4736). Mirror the debug scope.
    releaseImplementation(libs.androidx.compose.ui.test.manifest)
}

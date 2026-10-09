plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "co.fallsoft.pocket"
    compileSdk = 36
    defaultConfig { applicationId = "co.fallsoft.pocket"; minSdk = 28; targetSdk = 36; versionCode = 65; versionName = "0.5.0-alpha.47" }
    signingConfigs {
        if (System.getenv("POCKET_SIGNING_STORE") != null) {
            create("release") {
                storeFile = file(System.getenv("POCKET_SIGNING_STORE"))
                storePassword = System.getenv("POCKET_SIGNING_PASSWORD")
                keyAlias = System.getenv("POCKET_SIGNING_ALIAS") ?: "pocket"
                keyPassword = System.getenv("POCKET_SIGNING_KEY_PASSWORD") ?: System.getenv("POCKET_SIGNING_PASSWORD")
            }
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        getByName("debug") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("org.commonmark:commonmark:0.27.1")
    testImplementation("junit:junit:4.13.2")
    implementation(platform("androidx.compose:compose-bom:2025.10.00"))
    implementation("androidx.activity:activity-compose:1.12.4")
    // Prevent an older transitive Fragment from breaking ActivityResult permissions.
    implementation("androidx.fragment:fragment:1.8.9")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-extended:1.6.7")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("io.coil-kt:coil-svg:2.6.0")
    implementation("io.coil-kt:coil-gif:2.6.0")
    implementation("com.google.firebase:firebase-messaging:25.1.3")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
}

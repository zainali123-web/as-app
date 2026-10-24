plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.asapp"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.asapp.chat"
        minSdk = 24; targetSdk = 34
        versionCode = 10; versionName = "0.9"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("com.google.android.gms:play-services-nearby:19.1.0")
    implementation("io.getstream:stream-webrtc-android:1.3.9") }

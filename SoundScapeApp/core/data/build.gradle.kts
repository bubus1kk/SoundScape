plugins { alias(libs.plugins.android.library) }

android {
    namespace = "com.soundscape.app.core.data"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    lint { abortOnError = true }
}

dependencies { implementation(project(":core:domain")) }

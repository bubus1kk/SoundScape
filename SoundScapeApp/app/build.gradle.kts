import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)
}

val supabaseUrl = providers.gradleProperty("soundscape.supabaseUrl")
    .orElse(providers.environmentVariable("SOUNDSCAPE_SUPABASE_URL")).orElse("")
val supabasePublishableKey = providers.gradleProperty("soundscape.supabasePublishableKey")
    .orElse(providers.environmentVariable("SOUNDSCAPE_SUPABASE_PUBLISHABLE_KEY")).orElse("")
val mapTilerApiKey = providers.gradleProperty("soundscape.maptilerApiKey")
    .orElse(providers.environmentVariable("SOUNDSCAPE_MAPTILER_API_KEY")).orElse("")

val verifyDebugSupabaseConfiguration = tasks.register("verifyDebugSupabaseConfiguration") {
    group = "verification"
    description = "Checks Supabase configuration before packaging the Debug APK."
    inputs.property("supabaseUrl", supabaseUrl)
    inputs.property("supabasePublishableKey", supabasePublishableKey)
    doLast {
        val url = inputs.properties.getValue("supabaseUrl") as String
        val key = inputs.properties.getValue("supabasePublishableKey") as String
        val parsed = runCatching { URI(url) }.getOrNull()
        check(parsed != null && parsed.scheme == "https" && !parsed.host.isNullOrBlank() &&
            parsed.rawUserInfo == null && parsed.rawQuery == null && parsed.rawFragment == null &&
            parsed.rawPath in listOf("", "/")) {
            "Debug APK requires a valid HTTPS Supabase URL. Set soundscape.supabaseUrl in " +
                "Gradle user properties or SOUNDSCAPE_SUPABASE_URL."
        }
        check(key.startsWith("sb_publishable_") && key.none { it.isWhitespace() }) {
            "Debug APK requires a Supabase publishable key. Set soundscape.supabasePublishableKey in " +
                "Gradle user properties or SOUNDSCAPE_SUPABASE_PUBLISHABLE_KEY."
        }
    }
}

tasks.matching { it.name == "packageDebug" }.configureEach {
    dependsOn(verifyDebugSupabaseConfiguration)
}

android {
    namespace = "com.soundscape.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.soundscape.app"
        minSdk = 26
        targetSdk = 37
        fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
        buildConfigField("String", "SUPABASE_URL", quoted(supabaseUrl.get()))
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", quoted(supabasePublishableKey.get()))
        buildConfigField("String", "MAPTILER_API_KEY", quoted(mapTilerApiKey.get()))
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    lint { abortOnError = true }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

dependencies {
    implementation(libs.coil.core)
    implementation(libs.coil.compose.core)
    implementation(libs.kotlinx.coroutines.core)
    androidTestImplementation(libs.mockwebserver)
    androidTestImplementation(libs.okhttp)
    androidTestImplementation(libs.okhttp.tls)
    androidTestImplementation(libs.retrofit)
    androidTestImplementation(libs.retrofit.serialization)
    androidTestImplementation(libs.serialization.json)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(project(":core:network"))
    implementation(project(":presentation"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:data"))
    implementation(project(":core:domain"))
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(project(":core:database"))
    androidTestImplementation(libs.room.runtime)
    androidTestImplementation(libs.datastore.preferences)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.maplibre.opengl)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

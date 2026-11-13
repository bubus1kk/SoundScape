plugins {
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.detekt) apply false
}

// Plugin versions are resolved through the shared version catalog in each module.
tasks.register("detekt") {
    group = "verification"
    description = "Runs Kotlin static analysis in modules containing Kotlin sources."
    if (!providers.gradleProperty("domainOnly").map(String::toBoolean).getOrElse(false)) {
        dependsOn(":core:database:detekt", ":app:detekt", ":presentation:detekt", ":core:network:detekt", ":core:data:detekt")
    }
}

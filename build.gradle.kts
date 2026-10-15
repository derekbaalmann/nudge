plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Optional: put `nudge.buildDir=<folder>` in local.properties to build outside the project.
// On Windows that stops the IDE indexer and antivirus from locking intermediates
// ("Unable to delete directory").
val localProps = java.util.Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
(providers.gradleProperty("nudge.buildDir").orNull ?: localProps.getProperty("nudge.buildDir"))?.let { root ->
    allprojects {
        layout.buildDirectory.set(file("$root/${project.path.replace(':', '_').trim('_').ifEmpty { "root" }}"))
    }
}

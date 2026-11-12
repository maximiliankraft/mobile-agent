import java.util.Properties

plugins {
    id("com.android.application")
}

val signingProperties = Properties().apply {
    val propertiesFile = rootProject.file("signing.properties")
    if (propertiesFile.isFile) {
        propertiesFile.inputStream().use(::load)
    }
}

fun signingValue(property: String, environmentVariable: String): String? =
    providers.environmentVariable(environmentVariable).orNull?.takeIf { it.isNotBlank() }
        ?: signingProperties.getProperty(property)?.takeIf { it.isNotBlank() }

val sharedSigningStoreFile = signingValue("storeFile", "MOBILE_AGENT_KEYSTORE_FILE")
val sharedSigningStorePassword = signingValue("storePassword", "MOBILE_AGENT_KEYSTORE_PASSWORD")
val sharedSigningKeyAlias = signingValue("keyAlias", "MOBILE_AGENT_KEY_ALIAS")
val sharedSigningKeyPassword = signingValue("keyPassword", "MOBILE_AGENT_KEY_PASSWORD")
val sharedSigningValues = listOf(
    sharedSigningStoreFile,
    sharedSigningStorePassword,
    sharedSigningKeyAlias,
    sharedSigningKeyPassword,
)
val sharedSigningConfigured = sharedSigningValues.all { !it.isNullOrBlank() }

if (sharedSigningValues.any { !it.isNullOrBlank() } && !sharedSigningConfigured) {
    throw GradleException("Shared signing requires a keystore file, store password, key alias, and key password.")
}

android {
    namespace = "com.maximiliankraft.mobileagent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.maximiliankraft.mobileagent"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    if (sharedSigningConfigured) {
        signingConfigs {
            create("shared") {
                storeFile = rootProject.file(sharedSigningStoreFile!!)
                storePassword = sharedSigningStorePassword
                keyAlias = sharedSigningKeyAlias
                keyPassword = sharedSigningKeyPassword
            }
        }
        buildTypes {
            getByName("debug") {
                signingConfig = signingConfigs.getByName("shared")
            }
            getByName("release") {
                signingConfig = signingConfigs.getByName("shared")
            }
        }
    }

    dependencies {
        testImplementation("junit:junit:4.13.2")
    }
}

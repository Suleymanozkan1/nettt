import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Backend configuration and keys come from local.properties or environment variables, never from source control.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun config(name: String): String = (localProps.getProperty(name) ?: System.getenv(name.uppercase().replace('.', '_')) ?: "").trim()

// Gemini model and summary settings live in one committed file, ai-config.properties (no secrets in it).
val aiConfig = Properties().apply {
    rootProject.file("ai-config.properties").inputStream().use { load(it) }
}
fun aiSetting(name: String): String = aiConfig.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }
    ?: throw GradleException("ai-config.properties: '$name' is missing")
fun aiNumber(name: String): String = aiSetting(name).also {
    if (it.toDoubleOrNull() == null) throw GradleException("ai-config.properties: '$name' must be a number, was '$it'")
}
// The Gemini API key: local.properties (gemini.apiKey=…) or the GEMINI_API_KEY environment variable; never committed.
val geminiApiKey = (localProps.getProperty("gemini.apiKey") ?: System.getenv("GEMINI_API_KEY") ?: "").trim()
fun quoted(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.bookmind.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bookmind.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SUPABASE_URL", "\"${config("bookmind.supabaseUrl")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${config("bookmind.supabaseAnonKey")}\"")
        // The paid cloud AI (Supabase Edge Function → hosted LLM) is compiled out unless explicitly enabled.
        // Default builds only use free, local AI: no API key, no per-request cost, book text stays on the user's devices.
        buildConfigField("boolean", "CLOUD_AI_ENABLED", config("bookmind.enableCloudAi").equals("true", ignoreCase = true).toString())

        buildConfigField("String", "GEMINI_API_KEY", quoted(geminiApiKey))
        buildConfigField("String", "GEMINI_MODEL", quoted(aiSetting("gemini.model")))
        buildConfigField("String", "GEMINI_THINKING_LEVEL", quoted(aiConfig.getProperty("gemini.thinkingLevel")?.trim().orEmpty()))
        buildConfigField("int", "GEMINI_MAX_RETRIES", aiNumber("gemini.maxRetries").toDouble().toInt().toString())
        buildConfigField("double", "SUMMARY_CHAPTER_RATIO", aiNumber("summary.chapterRatio"))
        buildConfigField("int", "SUMMARY_OVERALL_MIN_WORDS", aiNumber("summary.overallMinWords").toDouble().toInt().toString())
        buildConfigField("int", "SUMMARY_OVERALL_MAX_WORDS", aiNumber("summary.overallMaxWords").toDouble().toInt().toString())
        buildConfigField("int", "SUMMARY_CHUNK_MIN_WORDS", aiNumber("summary.chunkMinWords").toDouble().toInt().toString())
        buildConfigField("int", "SUMMARY_CHUNK_MAX_WORDS", aiNumber("summary.chunkMaxWords").toDouble().toInt().toString())
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Release signing is configured by CI/keystore owners; see README. Debug key is used for local release checks.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        // Exported Room schemas are debug assets so MigrationTestHelper (Robolectric) can verify every migration.
        // Release builds do not include them.
        getByName("debug").assets.srcDir("$projectDir/schemas")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = false
            all { it.maxHeapSize = "2g" }
        }
    }

    // MediaPipe (on-device LLM) and ML Kit OCR ship large native libraries for every CPU type. One APK per ABI keeps
    // the phone download small (arm64-v8a covers practically all current phones); the universal APK runs everywhere.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = false
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    ksp(libs.kotlin.metadata.jvm)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.pdfbox.android)
    implementation(libs.mlkit.text.recognition)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.room.testing)
    testImplementation(libs.work.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
    kspTest(libs.kotlin.metadata.jvm)
}

tasks.withType<Test>().configureEach {
    System.getProperty("bookmind.screenshots")?.let { systemProperty("bookmind.screenshots", it) }
}

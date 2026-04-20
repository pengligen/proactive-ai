plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun escapeBuildConfigString(value: String): String {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}

val hfToken = providers.gradleProperty("HF_TOKEN")
    .orElse(providers.environmentVariable("HF_TOKEN"))
    .orNull
    .orEmpty()

val openAiApiKey = providers.gradleProperty("OPENAI_API_KEY")
    .orElse(providers.environmentVariable("OPENAI_API_KEY"))
    .orNull
    .orEmpty()

android {
    namespace = "com.proactiveai.extreme"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.proactiveai.extreme"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "HF_TOKEN", "\"${escapeBuildConfigString(hfToken)}\"")
        buildConfigField("String", "OPENAI_API_KEY", "\"${escapeBuildConfigString(openAiApiKey)}\"")
        buildConfigField(
            "String",
            "DEFAULT_GEMMA_E2B_TASK_URL",
            "\"https://huggingface.co/huggingworld/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it-web.task\"",
        )
        buildConfigField(
            "String",
            "DEFAULT_GEMMA_3N_E2B_TASK_URL",
            "\"https://huggingface.co/FUNFUN32/gemma-3n-E2B-it-int4.task/resolve/main/gemma-3n-E2B-it-int4.task\"",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-Xskip-metadata-version-check",
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("org.jetbrains:annotations:24.1.0")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.mediapipe:tasks-genai:0.10.27")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.10.0")
    implementation("com.google.mlkit:genai-speech-recognition:1.0.0-alpha1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

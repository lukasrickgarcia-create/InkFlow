plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.inkflow"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.inkflow"
        // O Vaio TL10 roda Android 13 (API 33). minSdk 26 mantém o app instalável em tablets mais antigos.
        minSdk = 26
        targetSdk = 35
        // Cada compilação no GitHub ganha um número maior: o Android sempre aceita como atualização.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
        versionCode = 100 + build
        versionName = "0.1.$build"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Chave fixa do projeto: toda compilação (no PC ou no GitHub) sai com a MESMA assinatura,
    // então novas versões instalam por cima sem apagar os cadernos.
    // Uso pessoal: a senha está aqui de propósito. Para publicar na Play Store, use uma chave secreta.
    signingConfigs {
        create("inkflow") {
            storeFile = file("inkflow-dev.keystore")
            storePassword = "inkflow123"
            keyAlias = "inkflow"
            keyPassword = "inkflow123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("inkflow")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("inkflow")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

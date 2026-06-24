import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// Signing-Credentials aus der gitignored keystore.properties (siehe keystore.properties.example).
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) FileInputStream(keystorePropertiesFile).use { load(it) }
}

// Fail-fast: ein signiertes Release darf NIE versehentlich unsigniert entstehen.
// Greift nur, wenn ein Release-Packaging-Task explizit angefordert wurde
// (Konfigurationszeit-Check → configuration-cache-kompatibel).
val wantsSignedRelease = gradle.startParameter.taskNames.any {
    it.contains("assembleRelease") || it.contains("bundleRelease")
}
if (wantsSignedRelease && !keystorePropertiesFile.exists()) {
    throw GradleException(
        "keystore.properties fehlt — ein signierter Release-Build ist nicht möglich. " +
            "Kopiere keystore.properties.example nach keystore.properties und trage deine " +
            "Keystore-Daten ein (Anleitung siehe README)."
    )
}

android {
    namespace = "io.github.lycheeappf.tmm"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.lycheeappf.tmm"
        minSdk = 33
        targetSdk = 36
        versionCode = 6
        versionName = "0.6.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // i18n: unterstützte Sprachen. Default-Resources (values/) sind Englisch,
        // values-de/ liefert die deutsche Übersetzung. Begrenzt zugleich die
        // mitgelieferten Library-Locales auf en+de.
        resourceConfigurations += setOf("en", "de")
    }


    signingConfigs {
        create("release") {
            // Leer, falls keystore.properties fehlt — ein Release-Build bricht dann
            // bewusst ab (assembleRelease-Check am Dateiende), statt unsigniert zu sein.
            if (keystorePropertiesFile.exists()) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
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

    lint {
        // NonNullableMutableLiveDataDetector (Issue "NullSafeMutableLiveData") crasht in
        // lintVitalRelease — bekannter interner Lint-Bug, nicht durch App-Code verursacht.
        disable += "NullSafeMutableLiveData"
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // Erzeugt locales_config.xml automatisch aus den vorhandenen values-*/-Ordnern
        // und injiziert android:localeConfig ins Manifest → aktiviert den OS-Sprach-Picker
        // (Einstellungen › Apps › TMM › Sprache). Default-Locale via res/resources.properties.
        generateLocaleConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    sourceSets {
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.startup)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.androidx.junit.ext)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.truth)
}

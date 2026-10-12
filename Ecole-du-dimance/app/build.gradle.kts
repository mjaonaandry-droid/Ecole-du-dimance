import com.android.build.api.artifact.SingleArtifact
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Signature de la version release : fichier « keystore.properties » à la racine, jamais versionné.
// Sans ce fichier, la release reste non signée (donc non installable) : voir le README.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}

// Clé PERMANENTE de l'application (depuis la 1.1.0). Android n'installe une mise à jour par-dessus la
// version existante (données conservées) QUE si elle est signée avec la même clé. La clé de débogage
// créée automatiquement change à chaque machine (et à chaque build GitHub Actions) : elle ne convient pas.
// La clé permanente est fournie par des variables d'environnement (secrets GitHub Actions en CI) :
//   SIGNATURE_KEYSTORE_FILE  chemin du fichier ecole-dimanche.keystore
//   SIGNATURE_PASSWORD       mot de passe (keystore et clé)
//   SIGNATURE_ALIAS          alias de la clé (par défaut « ecoledimanche »)
// Sans ces variables, l'APK est signé avec la clé de débogage locale et ne peut PAS mettre à jour
// l'application installée sur le téléphone.
val cleePermanenteFichier: File? = System.getenv("SIGNATURE_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }?.let { file(it) }
val cleePermanenteMotDePasse: String? = System.getenv("SIGNATURE_PASSWORD")?.takeIf { it.isNotBlank() }
val cleePermanenteAlias: String = System.getenv("SIGNATURE_ALIAS")?.takeIf { it.isNotBlank() } ?: "ecoledimanche"
val cleePermanenteDisponible = cleePermanenteFichier?.exists() == true && cleePermanenteMotDePasse != null

android {
    // L'identifiant technique ne doit plus changer après la première installation :
    // il conditionne les mises à jour. Le nom affiché se règle dans res/values/strings.xml.
    namespace = "mg.ecoledimanche.presences"
    compileSdk = 36

    defaultConfig {
        applicationId = "mg.ecoledimanche.presences"
        minSdk = 26
        targetSdk = 36
        // Toujours augmenter versionCode à chaque nouvelle version installée sur le téléphone.
        versionCode = 2
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (cleePermanenteDisponible) {
            create("permanente") {
                storeFile = cleePermanenteFichier
                storePassword = cleePermanenteMotDePasse
                keyAlias = cleePermanenteAlias
                keyPassword = cleePermanenteMotDePasse
            }
        }
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // L'APK installé sur le téléphone est la variante « debug » : il doit porter la clé permanente.
            if (cleePermanenteDisponible) signingConfig = signingConfigs.getByName("permanente")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // Les schémas Room exportés servent aussi aux tests de migration instrumentés.
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    // Export du schéma Room à chaque compilation : à versionner avec le code.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.concurrent.futures)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

// ---------------------------------------------------------------------------------------------
// Garde-fou « 100 % hors ligne » : échoue si le manifeste FUSIONNÉ (celui de l'application plus
// celui de toutes les dépendances) déclare la permission INTERNET.
// ---------------------------------------------------------------------------------------------
abstract class VerifierSansInternetTask : DefaultTask() {
    @get:InputFile
    abstract val manifesteFusionne: RegularFileProperty

    @TaskAction
    fun verifier() {
        val contenu = manifesteFusionne.get().asFile.readText()
        if (contenu.contains("android.permission.INTERNET")) {
            throw GradleException(
                "La permission INTERNET figure dans le manifeste fusionné " +
                    "(${manifesteFusionne.get().asFile}). L'application doit rester 100 % hors ligne.",
            )
        }
    }
}

androidComponents {
    onVariants { variant ->
        val suffixe = variant.name.replaceFirstChar { it.uppercase() }
        val verification = tasks.register<VerifierSansInternetTask>("verifierSansInternet$suffixe") {
            group = "verification"
            description = "Vérifie l'absence de la permission INTERNET dans le manifeste fusionné ($suffixe)."
            manifesteFusionne.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        }
        tasks.matching { it.name == "assemble$suffixe" }.configureEach {
            dependsOn(verification)
        }
    }
}

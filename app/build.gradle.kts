import java.util.Properties

plugins {
    alias(libs.plugins.android)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.compose)
    alias(libs.plugins.kapt)
}
val signingFile = providers.gradleProperty("chitalkaSigningFile").orNull?.let { file(it) }
    ?: file("${System.getProperty("user.home")}/.codex/private/chitalka/signing.properties")
val privateSigning = Properties().apply { if (signingFile.isFile) signingFile.inputStream().use { load(it) } }
if (gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) } && !signingFile.isFile) {
    error("Для release укажите -PchitalkaSigningFile=/private/path/signing.properties. Ключи не входят в репозиторий.")
}
// Some Windows JVM test runners cannot resolve Cyrillic classpath entries.
// Keep the source checkout in place and optionally put generated outputs on an ASCII path.
providers.gradleProperty("chitalkaBuildRoot").orNull?.let {
    layout.buildDirectory.set(file("$it/app"))
}
android {
    namespace = "ru.chitalka.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "ru.chitalka.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "1.0.0"
        providers.gradleProperty("chitalkaValidationIdSuffix").orNull?.let { applicationIdSuffix = it }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testBuildType = providers.gradleProperty("chitalkaTestBuildType").getOrElse("debug")
    signingConfigs {
        if (signingFile.isFile) create("stable") {
            storeFile = file(privateSigning.getProperty("storeFile"))
            storePassword = privateSigning.getProperty("storePassword")
            keyAlias = privateSigning.getProperty("keyAlias")
            keyPassword = privateSigning.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            // Keep the proven Readium/Room integration intact for the first MVP release.
            isMinifyEnabled = false
            if (signingFile.isFile) signingConfig = signingConfigs.getByName("stable")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
}
kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.fragment:fragment-ktx:1.8.7")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)
    implementation("org.jsoup:jsoup:1.18.1")
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
}

val oauthPropsFile = rootProject.layout.projectDirectory.file("oauth.properties")
val oauthPropsRaw = providers.fileContents(oauthPropsFile).asText.orElse("")
val oauthProperties = Properties().apply {
  val text = oauthPropsRaw.get()
  if (text.isNotEmpty()) {
    load(text.reader())
  }
}

fun readOAuthClientId(key: String): String {
  val raw = (oauthProperties.getProperty(key)
    ?: providers.gradleProperty(key).orNull
    ?: providers.environmentVariable(key).orNull
    ?: "").trim()
  return raw.trim('"', '\'')
}

fun toBuildConfigStringLiteral(value: String): String {
  val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
  return "\"$escaped\""
}

val gmailOAuthClientId = readOAuthClientId("GMAIL_OAUTH_CLIENT_ID")
val outlookOAuthClientId = readOAuthClientId("OUTLOOK_OAUTH_CLIENT_ID")
val outlookConsumersOAuthClientId = readOAuthClientId("OUTLOOK_CONSUMERS_OAUTH_CLIENT_ID")
  .ifBlank { outlookOAuthClientId }

android {
  namespace = "com.kazemail.app"
  compileSdk = 36
  defaultConfig {
    applicationId = "com.kazemail.app"
    minSdk = 26
    targetSdk = 36
    versionCode = 12
    versionName = "0.8.2"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    manifestPlaceholders["appAuthRedirectScheme"] = "com.kazemail.app"

    buildConfigField("String", "GMAIL_OAUTH_CLIENT_ID", toBuildConfigStringLiteral(gmailOAuthClientId))
    buildConfigField("String", "OUTLOOK_OAUTH_CLIENT_ID", toBuildConfigStringLiteral(outlookOAuthClientId))
    buildConfigField(
      "String",
      "OUTLOOK_CONSUMERS_OAUTH_CLIENT_ID",
      toBuildConfigStringLiteral(outlookConsumersOAuthClientId),
    )
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = true
    shaders = false
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }

  testOptions {
    unitTests.isReturnDefaultValues = true
  }
}

kotlin {
  jvmToolchain(17)
}

ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
  arg("room.generateKotlin", "true")
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.serialization.json)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.paging.runtime.ktx)
  implementation(libs.androidx.paging.compose)
  debugImplementation(libs.androidx.compose.ui.tooling)
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Room 3 + FTS5 + SQLCipher
  implementation(libs.androidx.room3.runtime)
  implementation(libs.androidx.room3.paging)
  ksp(libs.androidx.room3.compiler)
  implementation(libs.sqlcipher.android)

  // WorkManager + OAuth2 (AppAuth + Custom Tabs)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.appauth)
  implementation(libs.androidx.browser)

  // UniFFI JNA runtime for Rust kazemail_core
  implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")

  // Local JVM unit tests (Room 3 + BundledSQLiteDriver for FTS5 trigram tests without emulator)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.androidx.room3.testing)
  testImplementation(libs.androidx.sqlite.bundled.jvm) {
    exclude(group = "androidx.sqlite", module = "sqlite-jvm")
  }
  testImplementation(libs.jna.jvm)

  // Instrumented tests
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)
  androidTestImplementation(libs.androidx.work.testing)
  androidTestImplementation(libs.kotlinx.coroutines.test)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
}

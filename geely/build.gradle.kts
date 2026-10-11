plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localAuthenticationAssets = providers.environmentVariable("DIPLAY_AUTH_ASSETS_DIR")
    .orNull?.let { file(it).canonicalFile }

android {
    namespace = "com.lllhys.geeplay"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.lllhys.geeplay"
        minSdk = 28
        targetSdk = 37
        versionCode = 3
        versionName = "0.2.17-geely.3"
    }

    localAuthenticationAssets?.let { sourceSets.getByName("main").assets.srcDir(it) }

    signingConfigs {
        create("release") {
            storeFile = file(
                providers.environmentVariable("ANDROID_KEYSTORE_PATH")
                    .getOrElse("missing-release-keystore.jks"),
            )
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").getOrElse("")
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").getOrElse("")
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").getOrElse("")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":shared"))
    implementation(project(":vehicle-geely"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
}

val credentialAssets = files(android.sourceSets.flatMap { source ->
    source.assets.directories.map { directory ->
        fileTree(directory) {
            include(
                "**/offline-mfi/**",
                "**/*.pk8",
                "**/*.p7b",
                "**/*.key",
                "**/*.pem",
                "**/*.p12",
                "**/*.pfx",
                "**/*.jks",
                "**/*.keystore",
            )
        }
    }
})

val rejectBundledCredentials by tasks.registering {
    group = "verification"
    description = "Reject unexpected credential files in GeePlay APK assets."
    val filesToCheck = credentialAssets
    val allowed = localAuthenticationAssets?.let { directory ->
        listOf("identity.pk8", "certificate.p7b")
            .map { directory.resolve("offline-mfi/$it").canonicalFile }
            .toSet()
    } ?: emptySet()
    inputs.files(filesToCheck)
    doLast {
        check(allowed.all { it.isFile }) { "Explicit local authentication assets are incomplete" }
        val unexpected = filesToCheck.files.filter { it.canonicalFile !in allowed }
        check(unexpected.isEmpty()) { "Unexpected credential files in APK assets" }
    }
}

tasks.named("preBuild") { dependsOn(rejectBundledCredentials) }

plugins {
    id("com.android.library")
}

android {
    namespace = "com.kooo.evcc.evccapi"
    compileSdk { version = release(36) }

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures { aidl = true }
    lint { abortOnError = false }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.16.1")
}

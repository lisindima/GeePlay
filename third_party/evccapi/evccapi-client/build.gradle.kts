plugins {
    id("com.android.library")
}

android {
    namespace = "com.kooo.evcc.evccapi.client"
    compileSdk { version = release(36) }

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint { abortOnError = false }
}

dependencies {
    api(project(":evccapi-contract"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.16.1")
}

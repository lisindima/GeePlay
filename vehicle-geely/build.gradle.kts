plugins {
    id("com.android.library")
}

val qnxPassword = providers.environmentVariable("QNX_PASS").getOrElse("")
fun quotedBuildString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""

android {
    namespace = "com.lllhys.geeplay.vehicle.geely"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 28
        buildConfigField("String", "QNX_PASSWORD", quotedBuildString(qnxPassword))
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    api(project(":shared"))
    implementation(project(":evccapi-client"))
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
}

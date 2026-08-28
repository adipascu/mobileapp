import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val fdroidBuild = providers.gradleProperty("fdroidBuild").map(String::toBooleanStrict).orElse(false).get()
if (!fdroidBuild) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

val properties = Properties().apply {
    try {
        load(rootDir.resolve("local.properties").reader())
    } catch (e: Exception) {
        println("local.properties file not found")
    }
}
val localReleaseBuild = properties["LOCAL_RELEASE_BUILD"]?.toString()?.toBooleanStrictOrNull() ?: false

// Hoisted out of the lambda below, which must not capture the project.
val providerFactory = providers

// Number of commits in the git history, so it always increases on main.
val gitVersionCode = providers.exec {
    isIgnoreExitValue = true
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.map {
    it.trim().toIntOrNull() ?: throw GradleException("Error reading current commit count")
}

// Newest tag anywhere in the repo, including on branches HEAD doesn't descend from.
val gitVersionName = providers.exec {
    isIgnoreExitValue = true
    commandLine("git", "rev-list", "--tags", "--max-count=1")
}.standardOutput.asText.flatMap { rev ->
    providerFactory.exec {
        isIgnoreExitValue = true
        commandLine("git", "describe", "--tags", rev.trim().ifEmpty { "HEAD" })
    }.standardOutput.asText
}.map { it.trim().ifEmpty { "unknown" } }

android {
    namespace = "coredevices.coreapp"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    if (!fdroidBuild && !localReleaseBuild) {
        signingConfigs {
            create("release") {
                storeFile = file("../keystore.jks")
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEYSTORE_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "coredevices.coreapp"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += if (fdroidBuild) {
                setOf("armeabi-v7a", "arm64-v8a", "x86_64")
            } else {
                setOf("armeabi-v7a", "arm64-v8a")
            }
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    if (fdroidBuild) {
        androidResources.ignoreAssetsPatterns.add("needle-pebble-ft-cq4.zip")
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            if (!fdroidBuild) {
                if (localReleaseBuild) {
                    signingConfig = signingConfigs.getByName("debug")
                    // Crashlytics regenerates a mapping-id resource every build
                    // (upToDateWhen=false), forcing aapt + a full R8 rerun even on
                    // null builds. Skip it for local release builds. Configured by
                    // name because the Crashlytics plugin classes are not on the
                    // F-Droid build's script classpath.
                    extensions.getByName("firebaseCrashlytics").withGroovyBuilder {
                        "setMappingFileUploadEnabled"(false)
                    }
                } else {
                    signingConfig = signingConfigs.getByName("release")
                }
            }
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        getByName("debug") {
            isMinifyEnabled = false
            isDebuggable = true
            if (!fdroidBuild) {
                extensions.getByName("firebaseCrashlytics").withGroovyBuilder {
                    "setMappingFileUploadEnabled"(false)
                }
            }
        }
    }
    sourceSets {
        if (fdroidBuild) {
            getByName("debug").manifest.srcFile("src/androidFdroid/AndroidManifest.xml")
            getByName("release").manifest.srcFile("src/androidFdroid/AndroidManifest.xml")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":composeApp"))
    // Components this module's manifest declares, so lint can resolve them.
    implementation(project(":util"))
    implementation(libs.androidx.core.ktx)
    if (fdroidBuild) {
        implementation(project(":health-stubs"))
    } else {
        implementation(libs.health.kmp)
    }

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.ktor.client.okhttp)
    androidTestImplementation(libs.koin.core)
    androidTestImplementation(libs.koin.android)
    androidTestImplementation(libs.coroutines)
    androidTestImplementation(libs.kotlin.test)
    if (fdroidBuild) {
        androidTestImplementation(project(":firebase-stubs"))
        androidTestImplementation(project(":cactus-stubs"))
    } else {
        androidTestImplementation(platform(libs.firebase.bom))
        androidTestImplementation(libs.firebase.auth)
        androidTestImplementation(project(":cactus"))
    }
    androidTestImplementation(project(":experimental"))
    androidTestImplementation(project(":libindex"))
    androidTestImplementation(project(":index-ai"))
    androidTestImplementation(project(":mcp"))
}

// Resolved at execution time — a configuration-time .get() makes every commit invalidate the
// configuration cache.
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach {
            it.versionCode.set(gitVersionCode)
            it.versionName.set(gitVersionName)
        }
    }
}

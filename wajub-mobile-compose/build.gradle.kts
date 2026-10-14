plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.vanniktech.maven.publish") version "0.34.0"
    signing
}

group = "com.wajub"
version = providers.gradleProperty("wajubVersion").get()

android {
    namespace = "com.wajub.mobile.ui"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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

    testOptions {
        // Robolectric runs the Compose UI tests on the JVM, with the module's resources.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    api(project(":wajub-mobile-core"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.stripe:stripe-android:21.2.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    // Operator and merchant logos (SVG and bitmaps).
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    // Dokka's bundled ASM can't read the `PermittedSubclasses` attribute Kotlin
    // 2.x emits for sealed classes, so KDoc generation crashes — ship an empty
    // javadoc jar instead (satisfies Central's requirement without invoking Dokka).
    configure(
        com.vanniktech.maven.publish.AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = true,
            // Dokka crashes on this project regardless of the javadocJar strategy used
            // (see comment above) — Android AAR publications don't strictly require one.
            publishJavadocJar = false,
        )
    )

    coordinates(group.toString(), "wajub-mobile-compose", version.toString())

    pom {
        name.set("wajub-mobile-compose")
        description.set("Jetpack Compose PaymentSheet for the Wajub Android checkout SDK.")
        url.set("https://docs.wajub.com/libraries/sdks/mobile")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                name.set("Wajub")
                email.set("hello@wajub.com")
                organization.set("Wajub")
                organizationUrl.set("https://wajub.com")
            }
        }
        scm {
            connection.set("scm:git:https://github.com/wajubhq/wajub-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/wajubhq/wajub-android.git")
            url.set("https://github.com/wajubhq/wajub-android")
        }
    }
}

signing {
    // CI signs with the in-memory key it gets from its secrets
    // (ORG_GRADLE_PROJECT_signingInMemoryKey); a workstation with gpg.
    if (!providers.gradleProperty("signingInMemoryKey").isPresent) {
        useGpgCmd()
    }
}

plugins {
    kotlin("jvm")
    id("com.vanniktech.maven.publish") version "0.34.0"
    signing
}

group = "com.wajub"
version = providers.gradleProperty("wajubVersion").get()

kotlin {
    jvmToolchain(17)
}

// SDK_VERSION (sent in X-Wajub-Client) comes from gradle.properties, so the
// version is written in one place.
val generateSdkVersion by tasks.registering {
    val sdkVersion = version.toString()
    val outputDir = layout.buildDirectory.dir("generated/sdkVersion")
    inputs.property("version", sdkVersion)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("com/wajub/mobile/SdkVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package com.wajub.mobile\n\n" +
                "/** This SDK's version, as published to Maven Central. */\n" +
                "const val SDK_VERSION: String = \"$sdkVersion\"\n",
        )
    }
}

sourceSets.main {
    kotlin.srcDir(generateSdkVersion)
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.json:json:20240303")
    implementation("com.pusher:pusher-java-client:2.4.4")

    testImplementation(kotlin("test"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    // Dokka's bundled ASM can't read the `PermittedSubclasses` attribute Kotlin
    // 2.x emits for sealed classes, so KDoc generation crashes — ship an empty
    // javadoc jar instead (satisfies Central's requirement without invoking Dokka).
    configure(
        com.vanniktech.maven.publish.KotlinJvm(
            javadocJar = com.vanniktech.maven.publish.JavadocJar.Empty(),
            sourcesJar = true,
        )
    )

    coordinates(group.toString(), "wajub-mobile-core", version.toString())

    pom {
        name.set("wajub-mobile-core")
        description.set("Core of the Wajub Android payment SDK.")
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

plugins {
    id("com.android.library") version "8.4.0"
    kotlin("android") version "1.9.24"
    kotlin("plugin.serialization") version "1.9.24"
    id("maven-publish")
    id("signing")
}

android {
    namespace = "studio.usergist.feedback"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"0.1.4\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.lifecycle:lifecycle-process:2.8.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("io.coil-kt:coil:2.6.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

group = "com.usergist"
version = "0.1.4"

publishing {
    repositories {
        maven {
            name = "centralBundle"
            url = uri(layout.buildDirectory.dir("central-staging"))
        }
    }

    publications {
        register<MavenPublication>("release") {
            afterEvaluate { from(components["release"]) }
            groupId = "com.usergist"
            artifactId = "feedback"
            version = "0.1.4"
            pom {
                name.set("userGist Feedback SDK")
                description.set("userGist mobile feedback SDK for Android")
                url.set("https://usergist.com/docs/sdks/android")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/license/mit")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("usergist")
                        name.set("userGist")
                        email.set("hello@usergist.com")
                    }
                }
                scm {
                    url.set("https://github.com/Future-Picnic/usergist-android")
                    connection.set("scm:git:https://github.com/Future-Picnic/usergist-android.git")
                    developerConnection.set("scm:git:ssh://git@github.com/Future-Picnic/usergist-android.git")
                }
            }
        }
    }
}

signing {
    val privateKey = providers.environmentVariable("GPG_PRIVATE_KEY").orNull
    val privateKeyPassword = providers.environmentVariable("GPG_PRIVATE_KEY_PASSWORD").orNull
    if (!privateKey.isNullOrBlank()) {
        useInMemoryPgpKeys(privateKey, privateKeyPassword)
        sign(publishing.publications["release"])
    }
}

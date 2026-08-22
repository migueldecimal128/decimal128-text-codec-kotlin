import org.gradle.plugins.signing.SigningExtension

group = "com.decimal128"
version = "0.9.0"

plugins {
    id("maven-publish")
    id("signing")
    id("com.vanniktech.maven.publish") version "0.36.0"
    kotlin("multiplatform") version "2.3.10"
}

repositories {
    mavenCentral()
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
kotlin {
    applyDefaultHierarchyTemplate()

    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvmToolchain(21)

    jvm { }
    macosArm64 { }
    macosX64 { }
    linuxX64 { }
    iosArm64 { }
    iosSimulatorArm64 { }
    js {
        nodejs {
            testTask {
                useMocha {
                    timeout = "10000"
                }
            }
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
            // Encode oracle for TestBidToString / TestHexBidToString. Test-only:
            // the published codec has no runtime dependencies.
            implementation("com.decimal128:decimal128-kotlin:0.9.0")
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.matching { it.name == "iosSimulatorArm64Test" }.configureEach {
    enabled = System.getenv("CI") != null
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates("com.decimal128", "decimal128-text-codec-kotlin", version.toString())

    pom {
        name.set("decimal128-text-codec-kotlin")
        description.set("Thin BID128 ↔ text codec for IEEE 754-2019 decimal128 for Kotlin Multiplatform.")
        url.set("https://github.com/migueldecimal128/decimal128-text-codec-kotlin")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set("migueldecimal128")
                name.set("MiguelDecimal128")
                email.set("miguel@decimal128.com")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/migueldecimal128/decimal128-text-codec-kotlin.git")
            developerConnection.set("scm:git:ssh://github.com/migueldecimal128/decimal128-text-codec-kotlin.git")
            url.set("https://github.com/migueldecimal128/decimal128-text-codec-kotlin")
        }
    }
}

extensions.getByType<SigningExtension>().useGpgCmd()

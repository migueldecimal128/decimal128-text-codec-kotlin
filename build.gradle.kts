import org.gradle.plugins.signing.SigningExtension
import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest

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
                    // The canonical table is 84,764 rows through the codec twice
                    // and takes a few seconds under Node; the 10s default was
                    // sized for suites that never touched it.
                    timeout = "300000"
                }
            }
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// The conformance corpus lives in decimal128-resources, mounted as a submodule
// at <repo>/resources. Nothing is copied into this repo, so every test task is
// handed the absolute corpus root and the loader actuals read it from there --
// which is what makes it independent of each runner's working directory (Node's
// is somewhere under build/, and the iOS simulator's is a sandbox container).
val corpusRoot = rootDir.resolve("resources").absolutePath

tasks.withType<Test>().configureEach {
    environment("DECIMAL128_RESOURCES", corpusRoot)
}

tasks.withType<KotlinJsTest>().configureEach {
    environment("DECIMAL128_RESOURCES", corpusRoot)
}

tasks.withType<KotlinNativeTest>().configureEach {
    environment("DECIMAL128_RESOURCES", corpusRoot)
}

// simctl strips the SIMCTL_CHILD_ prefix and forwards the rest to the simulated
// process, so this is how getenv("DECIMAL128_RESOURCES") resolves inside the
// simulator. The plain variable set above reaches simctl itself, not the test.
tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    environment("SIMCTL_CHILD_DECIMAL128_RESOURCES", corpusRoot)
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

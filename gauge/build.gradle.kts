plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.autocockpit.gauge"
    compileSdk = 34

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        disable += setOf("OldTargetApi")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
}

/**
 * JVM micro-benchmark for GaugeGeometry tessellation. Hands-rolled: warmup rounds plus
 * timed rounds, no benchmarking framework. Run with:
 *   ./gradlew :gauge:geometryBenchmark -PoutDir=results
 */
tasks.register<JavaExec>("geometryBenchmark") {
    group = "verification"
    description = "Runs GaugeGeometryBenchmark on the JVM and writes geometry_benchmark.json"
    dependsOn("testClasses")
    mainClass.set("com.autocockpit.gauge.bench.GaugeGeometryBenchmarkKt")
    classpath = files(
        layout.buildDirectory.dir("tmp/kotlin-classes/test"),
        layout.buildDirectory.dir("intermediates/javac/debugUnitTest/classes"),
        configurations.named("testRuntimeClasspath"),
    )
    args = listOf(rootProject.file("results").absolutePath)
    systemProperty("java.awt.headless", "true")
    outputs.upToDateWhen { false }
}

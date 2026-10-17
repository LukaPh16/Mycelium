plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.mycelium.engine"
    compileSdk = 36
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// docs/schema.sql is the single schema source: shipped as an asset, read directly by tests.
abstract class SchemaAsset : DefaultTask() {
    @get:InputFile abstract val schema: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @TaskAction fun copy() { schema.get().asFile.copyTo(outputDir.file("schema.sql").get().asFile, overwrite = true) }
}
val schemaAsset = tasks.register<SchemaAsset>("schemaAsset") { schema.set(rootProject.file("docs/schema.sql")) }
androidComponents.onVariants { v -> v.sources.assets?.addGeneratedSourceDirectory(schemaAsset, SchemaAsset::outputDir) }

kotlin { jvmToolchain(17) }

tasks.withType<Test>().configureEach {
    systemProperty("schemaPath", rootProject.file("docs/schema.sql").absolutePath)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
}

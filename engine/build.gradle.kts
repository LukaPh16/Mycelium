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
    systemProperty("trackersPath", file("src/main/assets/trackers.json").absolutePath)
    systemProperty("chatTemplatesPath", file("src/main/assets/chat_templates.json").absolutePath)
    systemProperty("packetTypesPath", file("src/main/assets/config/packet_types.json").absolutePath)
    systemProperty("simDemoPath", file("src/main/assets/sim/demo1.json").absolutePath)
    systemProperty("terrariumConfigPath", file("src/main/assets/config/terrarium.json").absolutePath)
    systemProperty("assetsDir", file("src/main/assets").absolutePath)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("androidx.core:core-ktx:1.17.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.xerial:sqlite-jdbc:3.50.3.0")   // test-only: real SQLite for fixture-DB stats tests
}

// Presets are data: every preset in organisms/index.json needs a preset.json and strips whose sizes match it.
val checkPresets = tasks.register<Exec>("checkPresets") {
    group = "verification"
    description = "Runs scripts/check_presets.py on assets/organisms."
    val script = rootProject.file("scripts/check_presets.py")
    val stamp = layout.buildDirectory.file("checkPresets.ok")
    inputs.dir("src/main/assets/organisms")
    inputs.file(script)
    outputs.file(stamp)
    commandLine("python3", script.absolutePath)
    doLast { stamp.get().asFile.writeText("ok") }
}
tasks.named("preBuild") { dependsOn(checkPresets) }

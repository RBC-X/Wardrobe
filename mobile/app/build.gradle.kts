import java.util.Properties
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose"); id("com.google.devtools.ksp") }
val signing = Properties().apply { val file=rootProject.file("signing.properties"); if(file.exists()) file.inputStream().use { load(it) } }
android {
    namespace="com.personal.wardrobe"
    compileSdk=36
    defaultConfig { applicationId="com.personal.wardrobe"; minSdk=29; targetSdk=36; versionCode=2; versionName="1.0.1"; testInstrumentationRunner="androidx.test.runner.AndroidJUnitRunner" }
    if(signing.isNotEmpty()) signingConfigs.create("personal") { storeFile=rootProject.file(signing.getProperty("storeFile")); storePassword=signing.getProperty("storePassword"); keyAlias=signing.getProperty("keyAlias"); keyPassword=signing.getProperty("keyPassword") }
    buildTypes { release { isMinifyEnabled=false; if(signing.isNotEmpty()) signingConfig=signingConfigs.getByName("personal") } }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget="17" }
    buildFeatures { compose=true; buildConfig=true }
    sourceSets["main"].assets.srcDir(rootProject.file("../shared"))
    sourceSets["main"].assets.srcDir(rootProject.file("../models"))
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(project(":domain"))
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.10.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    testImplementation("junit:junit:4.13.2")
}

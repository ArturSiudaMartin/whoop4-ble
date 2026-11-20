plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.whoop4.bloop.data"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Room's MigrationTestHelper reads the exported schemas as test assets (run under Robolectric).
    sourceSets.getByName("test").assets.directories.add("$projectDir/schemas")
    testOptions.unitTests.isIncludeAndroidResources = true
}

ksp {
    // Exported schemas are committed so every future migration can be verified.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(project(":core:protocol"))
    api(libs.room.runtime)   // BloopDatabase extends RoomDatabase, so consumers need it
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
}

plugins {
    id("com.android.library")
}

// A vector overlay alone leaves the original unqualified PNG packaged for old APIs.
// Preserve the upstream source, but stage only distributable resources for TS7 builds.
val ts7MainResources = layout.buildDirectory.dir("generated/ts7-resources/main")
val stageTs7Resources by tasks.registering(Sync::class) {
    from("src/main/res") { exclude("drawable/ic_carplay.png") }
    from("src/debug/res/drawable/ic_carplay.xml") { into("drawable") }
    into(ts7MainResources)
}

android {
    namespace = "com.shilapi.xcertplay.host"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 19
        multiDexEnabled = true
    }
    // AGP9.3's legacy library DSL narrows to an interface its source-set object
    // does not implement. Use the verified public base source-set interface.
    val mainSource = (sourceSets as NamedDomainObjectContainer<*>).getByName("main")
        as com.android.build.api.dsl.AndroidSourceSet
    mainSource.res.directories.apply { clear(); add(ts7MainResources.get().asFile.absolutePath) }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }

}

tasks.named("preBuild") { dependsOn(stageTs7Resources) }

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    api(project(":shared"))
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}

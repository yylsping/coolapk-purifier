plugins {
    id("java")
    id("application")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

application {
    mainClass.set("io.github.yylsping.manifestgen.Main")
}

// Resolve default paths (baseline manifest, output dir) from the repo root.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

dependencies {
    implementation("org.smali:dexlib2:2.5.2")
    implementation("org.json:json:20240303")
}

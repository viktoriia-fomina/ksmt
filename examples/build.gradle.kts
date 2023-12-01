plugins {
    kotlin("jvm") version "1.7.20"
    java
}

repositories {
    mavenCentral()
}

dependencies {
    // core
    implementation("io.ksmt:ksmt-core:0.5.15")
    // z3 solver
    implementation("io.ksmt:ksmt-z3:0.5.15")
    // Runner and portfolio solver
    implementation("io.ksmt:ksmt-runner:0.5.15")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}
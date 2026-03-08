plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.jlleitschuh.gradle.ktlint")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter:3.3.0")
}

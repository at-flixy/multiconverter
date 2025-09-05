plugins {
    kotlin("jvm") version "1.9.24"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

repositories { mavenCentral() }

dependencies {
    val vertx = "4.5.9"
    implementation("io.vertx:vertx-core:$vertx")
    implementation("io.vertx:vertx-web:$vertx")
    implementation("io.vertx:vertx-web-client:$vertx")
    implementation("io.vertx:vertx-lang-kotlin:$vertx")
    implementation("io.vertx:vertx-lang-kotlin-coroutines:$vertx")
    implementation("ch.qos.logback:logback-classic:1.5.6")
}

application { mainClass.set("com.example.gateway.MainKt") }

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("gateway")
    archiveClassifier.set("all")
    archiveVersion.set("")
    mergeServiceFiles()
}
tasks.build { dependsOn("shadowJar") }

plugins {
    `java-library`
    alias(libs.plugins.clojurephant)
    `maven-publish`
    signing
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
    alias(libs.plugins.protobuf)
}

publishing {
    publications.create("maven", MavenPublication::class) {
        pom {
            name.set("XTDB kdb+ Tickerplant Source")
            description.set("XTDB kdb+ Tickerplant Source")
        }
    }
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(21))

// The demo: a fake tickerplant and two example indexers, in demo/. Own source set so they stay out of the module's jar.
val demo: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

kotlin.sourceSets.getByName("demo").kotlin.srcDir("demo/src/main/kotlin")
sourceSets.getByName("demo").resources.srcDir("demo/src/main/resources")

configurations[demo.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[demo.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

dependencies {
    api(project(":xtdb-api"))
    api(project(":xtdb-core"))

    api(kotlin("stdlib"))
    api(libs.kotlinx.serialization.json)
    api(libs.protobuf.kotlin)

    implementation(libs.kotlinx.coroutines)
    implementation(libs.javakdb)

    "demoRuntimeOnly"(project(":xtdb-main"))

    testImplementation(demo.output)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotest)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.asProvider().get()}"
    }

    generateProtoTasks {
        all().forEach {
            it.builtins {
                create("kotlin")
            }
        }
    }
}

dokka {
    modulePath.set("xtdb-kdb")
}

val demoStateDir = layout.buildDirectory.dir("demo-state")

// ./gradlew :modules:xtdb-kdb:demoNode - XTDB on pgwire :5432, state under modules/kdb/build/demo-state (wiped each run)
tasks.register<JavaExec>("demoNode") {
    group = "demo"
    classpath = demo.runtimeClasspath
    mainClass.set("clojure.main")
    args("-m", "xtdb.main", "-f", "modules/kdb/demo/config.yaml")
    workingDir = rootProject.projectDir
    jvmArgs(
        "--add-opens=java.base/java.nio=ALL-UNNAMED", "--enable-native-access=ALL-UNNAMED",
        "-Dio.netty.tryReflectionSetAccessible=true", "-Dio.netty.noUnsafe=false",
    )
    doFirst { delete(demoStateDir) }
}

// ./gradlew :modules:xtdb-kdb:demoTickerplant - a stand-in kdb+tick tickerplant on :5010
tasks.register<JavaExec>("demoTickerplant") {
    group = "demo"
    classpath = demo.runtimeClasspath
    mainClass.set("xtdb.kdb.demo.FakeTickerplantMainKt")
    args("5010")
}

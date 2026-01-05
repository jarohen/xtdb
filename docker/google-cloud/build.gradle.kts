import xtdb.DataReaderTransformer

plugins {
    java
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":xtdb-main"))
    implementation(project(":modules:xtdb-kafka"))
    implementation(project(":modules:xtdb-google-cloud"))

    runtimeOnly(libs.logback.classic)
    runtimeOnly(libs.slf4j.jpl)
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(21))

tasks.jar {
    manifest {
        attributes(
            "Implementation-Version" to project.version,
        )
    }
}

tasks.shadowJar {
    archiveBaseName.set("xtdb")
    archiveVersion.set("")
    archiveClassifier.set("google-cloud")
    mergeServiceFiles()
    transform(DataReaderTransformer())
    manifest {
        attributes("Main-Class" to "clojure.main")
    }
}

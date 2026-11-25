plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    useJUnit()
}

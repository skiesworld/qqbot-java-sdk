plugins {
    `java-library`
    id("com.vanniktech.maven.publish") version "0.37.0"
}

// project.group/version drive the jar names and the POM coordinates alike
group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

repositories {
    mavenCentral()
}

dependencies {
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("com.google.code.gson:gson:2.11.0")
    implementation("org.slf4j:slf4j-api:2.0.13")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.13")
}

// A logging binding must never become a transitive dependency of a library, so the examples get
// their own configuration: custom configurations are absent from the published POM.
val exampleRuntime: Configuration by configurations.creating {
    isCanBeResolved = true
}

dependencies {
    exampleRuntime("org.slf4j:slf4j-simple:2.0.13")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).charSet = "UTF-8"
    (options as StandardJavadocDocletOptions).docEncoding = "UTF-8"
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// Sources jar, javadoc jar, GPG signatures, .asc/.md5/.sha1 handling and the Central deployment
// are all arranged by the plugin; the values below are what Central validates the POM against.
mavenPublishing {
    val groupId = providers.gradleProperty("GROUP").get()
    val artifactId = providers.gradleProperty("POM_ARTIFACT_ID").get()
    val projectUrl = providers.gradleProperty("POM_URL").get()

    coordinates(groupId = groupId, artifactId = artifactId,
        version = providers.gradleProperty("VERSION_NAME").get())
    // automatic release is driven by mavenCentralAutomaticPublishing in gradle.properties,
    // which keeps the setup stable across plugin versions
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set(providers.gradleProperty("POM_NAME"))
        description.set(providers.gradleProperty("POM_DESCRIPTION"))
        inceptionYear.set("2026")
        url.set(projectUrl)
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        // developers come from POM_DEVELOPER_ID / POM_DEVELOPER_NAME in gradle.properties
        scm {
            url.set(projectUrl)
            connection.set("scm:git:$projectUrl")
            developerConnection.set("scm:git:ssh://github.com/" +
                providers.gradleProperty("POM_GITHUB_REPOSITORY").get() + ".git")
        }
    }
}

tasks.register<JavaExec>("runExample") {
    group = "application"
    description = "Runs an example: ./gradlew runExample -Pexample=EchoBot"
    classpath = sourceSets["main"].runtimeClasspath + exampleRuntime
    mainClass.set("io.github.skiesworld.qqbot.examples." + (project.findProperty("example") ?: "EchoBot").toString())
}

plugins { kotlin("jvm") version "2.4.21"; application }
group = "io.github.almeida96"
version = "0.1.0"
repositories { mavenCentral() }
dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.4")
    implementation("org.xerial:sqlite-jdbc:3.54.1.0")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}
kotlin { jvmToolchain(21) }
application {
    mainClass = "io.github.almeida96.queuesift.MainKt"
    applicationDefaultJvmArgs = listOf("-Xmx512m")
}
sourceSets.main { resources.srcDir("examples") }
tasks.test { useJUnitPlatform(); maxHeapSize = "512m" }
dependencyLocking { lockAllConfigurations() }
distributions { main { contents {
    from("README.md", "LICENSE", "CHANGELOG.md", "CONTRIBUTING.md")
    from("docs") { into("docs") }
    from("examples") { into("examples") }
} } }

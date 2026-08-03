import org.gradle.api.file.DuplicatesStrategy
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.25"
}

group = "com.mieai.bot"
version = providers.gradleProperty("pluginVersion").orElse("0.0.5").get()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
}

val qqbotSdkVersion = providers.gradleProperty("qqbotSdkVersion").orElse("1.0.6")
val qqbotSdkRepository = providers.gradleProperty("qqbotSdkRepository")
    .orElse(providers.environmentVariable("QQBOT_SDK_REPOSITORY"))
    .orElse("../miebot/build/plugin-sdk/repository")
val sqliteJdbcVersion = "3.49.1.0"

repositories {
    maven { url = uri(qqbotSdkRepository.get()) }
    mavenCentral()
}

dependencies {
    // MieBot owns these classes. Keeping them out of the fat JAR avoids a second API copy.
    compileOnly("com.mieai.qqbot:qqbot-plugin-api:${qqbotSdkVersion.get()}")
    compileOnly("com.mieai.qqbot:qqbot-plugin-spi:${qqbotSdkVersion.get()}")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.3")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.3")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.3")

    // MieBot provides this driver through PF4J's application parent classloader.
    // A second copy in the plugin JAR would try to load sqlitejdbc native code twice.
    compileOnly("org.xerial:sqlite-jdbc:$sqliteJdbcVersion")

    testImplementation("com.mieai.qqbot:qqbot-plugin-testkit:${qqbotSdkVersion.get()}")
    testImplementation("org.pf4j:pf4j:3.13.0")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.xerial:sqlite-jdbc:$sqliteJdbcVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        javaParameters.set(true)
        freeCompilerArgs.addAll(listOf("-Xjsr305=strict", "-Xjvm-default=all"))
    }
}

val pluginJar = tasks.named<Jar>("jar")

tasks.test {
    useJUnitPlatform()
    exclude("**/Pf4jSqliteParentLoaderIntegrationTest.class")
}

val pf4jSqliteTest by tasks.registering(Test::class) {
    description = "Verifies SQLite parent-classloader reuse through the packaged PF4J plugin"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    include("**/Pf4jSqliteParentLoaderIntegrationTest.class")
    dependsOn(pluginJar)
    dependsOn(tasks.testClasses)
    shouldRunAfter(tasks.test)
    doFirst {
        systemProperty(
            "mieai.plugin.jar",
            pluginJar.get().archiveFile.get().asFile.absolutePath,
        )
    }
}

tasks.check {
    dependsOn(pf4jSqliteTest)
}

tasks.jar {
    archiveBaseName.set("mieai-bot")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({ configurations.runtimeClasspath.get().map(::zipTree) }) {
        exclude(
            "META-INF/MANIFEST.MF",
            "META-INF/*.DSA",
            "META-INF/*.RSA",
            "META-INF/*.SF",
            "module-info.class",
            "META-INF/versions/*/module-info.class",
        )
    }
    manifest {
        attributes(
            "Plugin-Id" to "mieai-bot",
            "Plugin-Name" to "MieAI Bot",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Requires" to "3.2.0",
            "Plugin-Class" to "com.mieai.qqbot.plugin.host.Pf4jPluginBridge",
            "Plugin-Config-Schema" to "qqbot-plugin-schema.json",
            "Plugin-Default-Config" to "qqbot-plugin-default.yml",
            "Plugin-Capabilities" to "event.read,event.subscribe,message.send,scheduler",
        )
    }
}

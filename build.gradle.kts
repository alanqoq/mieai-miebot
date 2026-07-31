import org.gradle.api.file.DuplicatesStrategy
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.25"
}

group = "com.mieai.bot"
version = providers.gradleProperty("pluginVersion").orElse("0.0.1").get()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
}

val qqbotSdkVersion = providers.gradleProperty("qqbotSdkVersion").orElse("1.0.3")
val qqbotSdkRepository = providers.gradleProperty("qqbotSdkRepository")
    .orElse(providers.environmentVariable("QQBOT_SDK_REPOSITORY"))
    .orElse("../mirai-qqbot/build/plugin-sdk/repository")

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
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")

    testImplementation("com.mieai.qqbot:qqbot-plugin-testkit:${qqbotSdkVersion.get()}")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        javaParameters.set(true)
        freeCompilerArgs.addAll(listOf("-Xjsr305=strict", "-Xjvm-default=all"))
    }
}

tasks.test {
    useJUnitPlatform()
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

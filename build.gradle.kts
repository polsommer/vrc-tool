plugins {
    java
    application
}

group = "com.vrctool"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("net.dv8tion:JDA:5.0.0-beta.20")
    implementation("ch.qos.logback:logback-classic:1.4.14")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.1")
}

application {
    mainClass.set("com.vrctool.bot.BotLauncher")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

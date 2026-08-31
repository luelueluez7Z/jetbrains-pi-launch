plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// 目标 IntelliJ 平台版本可通过 -PintellijPlatformVersion=... 或
// gradle.properties 中的 intellijPlatformVersion 覆盖，避免依赖本机安装目录。
val intellijPlatformVersion = providers.gradleProperty("intellijPlatformVersion")
    .orElse("2026.2")

// Optional local IDE used for development verification. Example:
// ./gradlew runIde -PlocalIdePath="D:\\Program Files\\Jetbrains\\Programs\\IntelliJ IDEA Ultimate"
val localIdePath = providers.gradleProperty("localIdePath")

dependencies {
    intellijPlatform {
        // 从 JetBrains 仓库解析平台，不依赖开发机上的 IntelliJ 安装路径
        // 2025.3+ 使用统一的 IntelliJ IDEA 分发包，启动沙箱时不再拉取
        // 已停止发布的独立 Community 坐标。
        if (localIdePath.isPresent) {
            local(localIdePath.get())
        } else {
            intellijIdea(intellijPlatformVersion)
        }
        // JCEF is bundled with supported IntelliJ distributions, but it is an
        // optional platform module and must be present on the compile classpath.
        bundledPlugin("com.intellij.modules.jcef")
    }
    testImplementation(kotlin("test"))
}

java {
    toolchain {
        // IDEA 2026.2 平台类由 Java 25 编译（类文件版本 69），插件需用 Java 25 编译以匹配
        languageVersion = JavaLanguageVersion.of(25)
    }
}

kotlin {
    jvmToolchain(25)
}

intellijPlatform {
    projectName = "pichat"
    // 无 GUI 环境构建时禁用搜索选项索引（避免启动 IDEA AWT）
    buildSearchableOptions = false
    // 沙箱容器目录
    sandboxContainer = layout.projectDirectory.dir(".intellijPlatform/sandbox")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.test {
    useJUnitPlatform()
}

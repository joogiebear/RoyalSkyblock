import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    java
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.3.1"
    id("com.willfp.libreforge-gradle-plugin") version "2.0.0"
}

group = "com.mystipixel"
version = rootProject.property("version") as String

val ecoVersion = rootProject.property("eco-version") as String
val libreforgeVersion = rootProject.property("libreforge-version") as String

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.auxilor.io/repository/maven-public/")
    maven("https://jitpack.io")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    // Advanced Slime Paper (ASP) — the maintained SlimeWorld lineage, providing the per-island
    // world backend. RoyalSkyblock compiles against its API; the server runs the ASP fork.
    maven("https://repo.infernalsuite.com/repository/maven-snapshots/")
    // WorldEdit / FAWE API for schematic pasting (soft dependency).
    maven("https://maven.enginehub.org/repo/")
}

dependencies {
    // eco platform. Now a HARD dependency (plugin.yml `depend: [eco]`) — RoyalSkyblock is built
    // around the eco/libreforge element system rather than merely tolerating it.
    compileOnly("com.willfp:eco:$ecoVersion")
    compileOnly("org.jetbrains:annotations:26.0.2")
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.3.0")

    compileOnly("io.papermc.paper:paper-api:26.2.build.40-alpha")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7")

    // ASP world API. compileOnly because the ASP server fork ships these classes on the runtime
    // classpath. Only the world/asp adapter compiles against this.
    //
    // NOTE: the ASP fork exposes the world API but NOT the file/mysql loader classes on the plugin
    // classpath, so RoyalSkyblock ships its own SlimeLoader implementations (world.asp.loaders.*)
    // rather than depending on ASP's loader artifacts.
    compileOnly("com.infernalsuite.asp:api:4.2.0-SNAPSHOT")

    // JDBC drivers + connection pool are downloaded at runtime by Paper's library loader
    // (see plugin.yml `libraries`), so they are only needed here for compilation.
    compileOnly("org.xerial:sqlite-jdbc:3.46.1.3")
    compileOnly("com.zaxxer:HikariCP:5.1.0")
    compileOnly("com.mysql:mysql-connector-j:8.4.0")

    compileOnly("me.clip:placeholderapi:2.11.6")

    // EcoMobs API for the optional strength bridge + island mob spawning. compileOnly + soft-hooked:
    // RoyalSkyblock loads and runs fine without EcoMobs installed.
    //
    // DELIBERATELY PINNED off the eco version line: the published com.willfp:EcoMobs:2026.32
    // artifact is an empty stub (2 entries, no classes), so it cannot be compiled against. The
    // shipped 2026.32 plugin jar still contains com.willfp.ecomobs.event.EcoMobSpawnEvent with an
    // unchanged signature, so compiling against 2026.27 and running against 2026.32 is safe.
    // Re-check on each EcoMobs update; move this back onto $ecoVersion once upstream publishes again.
    compileOnly("com.willfp:EcoMobs:2026.27")

    // EcoSkills is read by REFLECTION (see EcoSkillsCombatSource), not a compile dependency: its
    // published POM drags in per-version NMS submodules that aren't resolvable, and reflection keeps
    // the hook genuinely soft (absent = mobs fall back to level 1).

    // WorldEdit API (FAWE implements it). Soft-dependency for schematic paste/save; every
    // com.sk89q.worldedit.* type is only touched when WorldEdit/FAWE is present at runtime.
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.6")

    // No bStats dependency: eco registers metrics itself from the id in eco.yml, and the plugin
    // supplies its charts through EcoPlugin.getCustomCharts(). Shading our own copy as well would
    // double-report.

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.mockito:mockito-core:5.14.2")
    // Platform version tracks junit-jupiter 5.11.3 (Maven's surefire supplied this implicitly).
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.3")
}

// Mixed Java/Kotlin single module. Kotlin scans src/main/java too, so joint compilation resolves
// both directions: the Kotlin plugin main class references the Java subsystems, and the Java
// subsystems reference it back.
sourceSets {
    main {
        java.setSrcDirs(listOf("src/main/java"))
        kotlin.setSrcDirs(listOf("src/main/kotlin", "src/main/java"))
    }
}

// Compile with the local JDK (25) but emit Java 21 bytecode, matching eco/libreforge and the
// previous Maven `<release>21</release>`. No toolchain: it would require provisioning a second JDK.
java {
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

tasks.processResources {
    // Only plugin.yml and eco.yml carry ${...} placeholders. Expanding every resource would break
    // the GUI/config ymls, whose $ and % sequences are data, not templates.
    filesMatching(listOf("plugin.yml", "eco.yml")) {
        expand(
            "version" to project.version,
            "pluginName" to rootProject.name,
            "libreforgeVersion" to libreforgeVersion
        )
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveFileName.set("RoyalSkyblock.jar")
    exclude("META-INF/**")

    // Mandatory for libreforge plugins: each plugin relocates its own copy of the loader.
    relocate("com.willfp.libreforge.loader", "com.mystipixel.royalskyblock.libreforge.loader")

    // Match eco's own relocation so every eco plugin shares one Kotlin runtime.
    relocate("kotlin", "com.willfp.eco.libs.kotlin")
    relocate("kotlin.jvm", "com.willfp.eco.libs.kotlin.jvm")
    relocate("kotlin.coroutines", "com.willfp.eco.libs.kotlin.coroutines")
    relocate("kotlin.reflect", "com.willfp.eco.libs.kotlin.reflect")
}

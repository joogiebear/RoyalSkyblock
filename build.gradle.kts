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
    // Deliberately NO mavenLocal(). It served paper-api and the ASP API from ~/.m2, where they are
    // plain POMs with no Gradle module metadata, so the JVM-version constraint below was never
    // checked locally and CI was the first thing to notice. Resolving the same way everywhere is
    // worth more than the offline convenience.
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

    // Prebuilt eco GUI components (pagination, menuStateVar). Shipped in the jar rather than
    // compileOnly — eco does not provide it at runtime — and relocated below, which is how the
    // Auxilor plugins consume it. Used by the menu engine's port onto eco's Menu API.
    implementation("com.willfp:ecomponent:1.5.0")

    compileOnly("org.jetbrains:annotations:26.0.2")
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.3.0")

    compileOnly("io.papermc.paper:paper-api:26.2.build.40-alpha")

    // isTransitive = false on the two API jars below. Maven's `provided` scope quietly ignored their
    // dependency trees; Gradle resolves them properly and then refuses to guess, because both
    // conflict with paper-api:
    //   - VaultAPI drags org.bukkit:bukkit:1.13.1, which declares the same `org.bukkit:bukkit`
    //     capability as paper-api ("Cannot select module with conflict on capability").
    //   - WorldEdit pins guava/gson/fastutil with `strictly` constraints ("Mojang provides Guava")
    //     that are older than the ones paper-api asks for.
    // We compile against both purely as API surfaces and the server provides the real classes, so
    // dropping their transitives is correct rather than merely expedient.
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") { isTransitive = false }

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

    // NO EcoMobs dependency. Every published com.willfp:EcoMobs artifact is an empty two-entry stub
    // with no classes — verified across 2026.27, .29, .30, .31 and .32 — so there is nothing to
    // compile against. Pinning to 2026.27 appeared to work only because a real jar had been
    // hand-installed into the local Maven repository, which CI cannot reproduce. EcoMobsStrengthBridge
    // and EcoMobsIslandMobProvider resolve EcoMobs reflectively at runtime instead, the same way
    // EcoSkillsCombatSource already does.

    // EcoSkills is read by REFLECTION (see EcoSkillsCombatSource), not a compile dependency: its
    // published POM drags in per-version NMS submodules that aren't resolvable, and reflection keeps
    // the hook genuinely soft (absent = mobs fall back to level 1).

    // WorldEdit API (FAWE implements it). Soft-dependency for schematic paste/save; every
    // com.sk89q.worldedit.* type is only touched when WorldEdit/FAWE is present at runtime.
    // Keeps worldedit-core (where the clipboard/schematic classes live) but drops the three modules
    // it pins with `strictly` constraints ("Mojang provides Guava"). Those pins are older than what
    // paper-api asks for, and Gradle treats the clash as unresolvable. The server supplies all three
    // at runtime, so nothing is lost by not resolving them here.
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.6") {
        exclude(group = "com.google.guava")
        exclude(group = "com.google.code.gson")
        exclude(group = "it.unimi.dsi")
    }

    // No bStats dependency: eco registers metrics itself from the id in eco.yml, and the plugin
    // supplies its charts through EcoPlugin.getCustomCharts(). Shading our own copy as well would
    // double-report.

    // eco on the test classpath so the menu coordinate conversion can be asserted against eco's own
    // MenuUtils rather than a hand-copied formula — a silent off-by-one there displaces every button.
    testImplementation("com.willfp:eco:$ecoVersion")

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

// Java 25, matching the rest of the suite. Not a free choice: paper-api 26.2 and the ASP API both
// publish Gradle module metadata declaring org.gradle.jvm.version = 25, so asking for an older
// target makes them unresolvable ("only compatible with JVM runtime version 25 or newer"). The
// server runs ASP 26.2 on Java 25 regardless. eco itself ships Java 21 bytecode, which a Java 25
// plugin calls without issue — the constraint runs one way.
java {
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
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

    // ecomponent is bundled, so it must be relocated too — otherwise two eco plugins shipping
    // different versions clash on the same classes.
    relocate("com.willfp.ecomponent", "com.mystipixel.royalskyblock.ecomponent")

    // Match eco's own relocation so every eco plugin shares one Kotlin runtime.
    relocate("kotlin", "com.willfp.eco.libs.kotlin")
    relocate("kotlin.jvm", "com.willfp.eco.libs.kotlin.jvm")
    relocate("kotlin.coroutines", "com.willfp.eco.libs.kotlin.coroutines")
    relocate("kotlin.reflect", "com.willfp.eco.libs.kotlin.reflect")
}

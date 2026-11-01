import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("com.gradleup.shadow") version "9.3.1"
    kotlin("jvm") version "2.3.20"
}

group = "im.apeki"
version = "1.2.0"

val kotlinRuntimeVersion = "2.3.20"
val worldGuardVersion = "7.0.13"
val decentHologramsVersion = "2.8.16"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly(kotlin("stdlib", kotlinRuntimeVersion))
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:$worldGuardVersion")
    compileOnly("com.github.decentsoftware-eu:decentholograms:$decentHologramsVersion")
}

tasks {
    compileJava {
        options.release.set(21)
    }

    compileKotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    processResources {
        val props = mapOf(
            "version" to project.version,
            "kotlinVersion" to kotlinRuntimeVersion,
        )

        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    jar {
        archiveClassifier.set("plain")
    }

    named<ShadowJar>("shadowJar") {
        archiveClassifier.set("")
        relocate("net.kyori", "im.apeki.shieldyblocks.shaded.kyori")
    }

    assemble {
        dependsOn("shadowJar")
    }

    register("printVersion") {
        group = "help"
        description = "Prints the project version for automation scripts."
        doLast {
            println(project.version)
        }
    }

    register("deploy") {
        group = "deployment"
        description = "Builds the plugin and deploys the jar to the local dev server plugins directory."
        dependsOn("build")

        doLast {
            val pluginsDir =
                file("${System.getProperty("user.home")}/Projects/Server/Dev/plugins")
            val artifact =
                named<ShadowJar>("shadowJar").get().archiveFile.get().asFile

            pluginsDir.mkdirs()
            delete(fileTree(pluginsDir) {
                include("${project.name}*.jar")
            })

            copy {
                from(artifact)
                into(pluginsDir)
            }

            logger.lifecycle("Deployed ${artifact.name} to $pluginsDir")
        }
    }
}
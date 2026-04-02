/*
 * GitHub Packages publishing convention for DMD Brands vico fork.
 * Credentials read from gradle-local.properties (gpr.user, gpr.token)
 * or environment variables (GITHUB_USERNAME, GITHUB_TOKEN).
 */

import java.util.Properties

plugins {
    `maven-publish`
    signing
}

val localProps = Properties().apply {
    val file = rootProject.file("gradle-local.properties")
    if (file.exists()) load(file.inputStream())
}

afterEvaluate {
    val component = components.findByName("release") ?: components.findByName("kotlin")
    if (component != null) {
        publishing {
            publications {
                create<MavenPublication>("gpr") {
                    groupId = "com.dmdbrands.lib"
                    artifactId = "vico-gg"
                    version = Versions.VICO
                    from(component)
                }
            }
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/dmdbrands/vico")
                    credentials {
                        username = localProps.getProperty("gpr.user")
                            ?: project.findProperty("gpr.user") as String?
                            ?: System.getenv("GITHUB_USERNAME")
                            ?: "Selva-GG"
                        password = localProps.getProperty("gpr.token")
                            ?: project.findProperty("gpr.token") as String?
                            ?: System.getenv("GITHUB_TOKEN")
                            ?: ""
                    }
                }
            }
        }
        // Skip signing for GPR publications
        signing {
            setRequired(false)
        }
        tasks.matching { it.name.contains("sign") && it.name.contains("Gpr") }.configureEach {
            enabled = false
        }
        // Also disable signing for KMP-generated publications routed to GPR
        tasks.matching { it.name.contains("sign") && it.name.contains("GitHubPackages") }.configureEach {
            enabled = false
        }
    }
}

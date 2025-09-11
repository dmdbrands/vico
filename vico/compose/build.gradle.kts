/*
 * Copyright 2025 by Patryk Goworowski and Patrick Michalik.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
  id("com.android.library")
  id("kotlin-android")
  id("org.jetbrains.kotlin.plugin.compose")
  id("maven-publish")
  `dokka-convention`
}

android {
  configure()
  kotlinOptions {
    jvmTarget = JavaVersion.VERSION_1_8.toString()
    freeCompilerArgs += listOf("-Xjsr305=strict", "-Xjvm-default=all")
  }
  namespace = moduleNamespace
}

kotlin { explicitApi() }

tasks.register<Jar>("sourcesJar") {
    archiveClassifier.set("sources")
    from(android.sourceSets.getByName("main").java.srcDirs)
}

composeCompiler { reportsDestination = layout.buildDirectory.dir("reports") }

dependencies {
  api(project(":vico:core"))
  implementation(libs.androidXCore)
  implementation(libs.appcompat)
  implementation(libs.composeFoundation)
  implementation(libs.composeUI)
  implementation(libs.kotlinStdLib)
  implementation(platform(libs.composeBom))
  testImplementation(libs.kotlinTest)
}

publishing {
    publications {
        create<MavenPublication>("gpr") {
            run {
                groupId = "com.dmdbrands.lib"
                artifactId = "vico-compose"
                version = Versions.VICO
                artifact("build/outputs/aar/compose-debug.aar")

                // Add sources JAR
                artifact(tasks.named("sourcesJar"))
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/dmdbrands/vico")
            credentials {
                username = System.getenv("GITHUB_USERNAME") ?: "VivekGG"
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

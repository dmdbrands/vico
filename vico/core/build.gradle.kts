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

dependencies {
  implementation(libs.androidXAnnotation)
  implementation(libs.coroutinesCore)
  implementation(libs.kotlinStdLib)
  implementation(libs.runtimeSaveable)
  compileOnly(libs.composeStableMarker)
  compileOnly(libs.composeRuntimeSaveable)
  testImplementation(libs.jupiter)
  testImplementation(libs.jupiterParams)
  testImplementation(libs.kotlinTest)
  testImplementation(libs.mockK)
  testImplementation(libs.testCore)
}

publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "com.dmdbrands.lib"
            artifactId = "vico-core"
            version = Versions.VICO
            artifact("build/outputs/aar/core-debug.aar")
            artifact(tasks.named("sourcesJar"))

            pom {
                name.set("Vico Core")
                description.set("Core chart library for Android")
            }
        }
    }
    repositories {
        mavenLocal()
    }
}

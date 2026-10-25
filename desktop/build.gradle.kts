import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    kotlin("plugin.compose") version "2.4.10"
    id("org.jetbrains.compose") version "1.12.2"
}

group = "com.metrolist.desktop"
version = "1.0.0"

kotlin {
    jvmToolchain(21)
}

// The desktop app compiles the platform-independent parts of the Android app directly from
// their original locations, so fixes there (e.g. Spotify hash rotations) reach both apps.
val sharedSources = listOf(
    "../innertube/src/main/kotlin",
    "../app/src/main/kotlin/com/metrolist/spotify",
    "../app/src/main/kotlin/com/metrolist/lrclib",
    "../app/src/main/kotlin/com/metrolist/kugou",
)

sourceSets.main {
    kotlin.srcDirs(sharedSources)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    implementation("com.github.MetrolistGroup.innertubex:innertubex:v0.5.2")

    implementation("io.ktor:ktor-client-core:3.5.2")
    implementation("io.ktor:ktor-client-okhttp:3.5.2")
    implementation("io.ktor:ktor-client-content-negotiation:3.5.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")
    implementation("io.ktor:ktor-client-encoding:3.5.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")

    implementation("io.coil-kt.coil3:coil-compose:3.6.0")
    implementation("io.coil-kt.coil3:coil-network-ktor3:3.6.0")

    implementation("uk.co.caprica:vlcj:4.12.1")

    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "com.metrolist.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Meld"
            packageVersion = version.toString()
            description = "Spotify and YouTube Music client"
            vendor = "Meld"
            modules("java.net.http", "jdk.httpserver", "jdk.crypto.ec", "java.sql")
            // Holds the bundled libVLC on Windows; the CI workflow downloads it into appResources/windows/vlc.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("appResources"))

            windows {
                menuGroup = "Meld"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "6f3c2a3e-6a8e-4f5b-9b52-6b0f5e7f0d11"
                iconFile.set(project.file("icons/meld.ico"))
            }
        }
    }
}

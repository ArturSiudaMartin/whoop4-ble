pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Bloop"

include(":app")
include(":core:protocol")   // pure Kotlin/JVM: framing, CRC, packet/record/event parsers
include(":core:ble")        // Android BLE: scanner, GATT queue, connection, live HR, sync engine
include(":core:data")       // Room database + repositories
include(":core:analytics")  // pure Kotlin/JVM: HRV, RHR, sleep, strain, recovery

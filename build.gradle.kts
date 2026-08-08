plugins {
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
}

tasks.register<Delete>("clean") {
    description = "Deletes the build directory."
    delete(rootProject.layout.buildDirectory)
}

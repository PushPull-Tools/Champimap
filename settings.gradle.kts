// Déclaration des dépôts et des modules du projet Champimap.
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

rootProject.name = "Champimap"

// app     : l'appli principale, SANS aucune permission Internet.
// connect : le module séparé « Champimap Connect » (cartes, météo, mises à jour).
include(":app")
include(":connect")

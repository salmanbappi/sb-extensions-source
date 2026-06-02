package keiyoushi.gradle.extensions

import org.gradle.accessors.dm.LibrariesForKei
import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.plugins.PluginManager
import org.gradle.kotlin.dsl.the

internal val Project.libs get() = the<LibrariesForLibs>()
internal val Project.kei get() = the<LibrariesForKei>()

internal fun Project.plugins(block: PluginManager.() -> Unit) {
    pluginManager.apply(block)
}

fun Project.spotlessTaskName() = if (providers.environmentVariable("CI").orNull != "true") "spotlessApply" else "spotlessCheck"

fun Project.getDependents(): Set<Project> {
    val dependentProjects = mutableSetOf<Project>()

    rootProject.allprojects.forEach { project ->
        try {
            project.configurations.forEach { configuration ->
                try {
                    // Only check resolvable configurations that can have dependencies
                    if (configuration.isCanBeResolved) {
                        configuration.dependencies.forEach { dependency ->
                            if (dependency is ProjectDependency && dependency.path == path) {
                                dependentProjects.add(project)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Skip configurations that can't be resolved or accessed
                }
            }
        } catch (e: Exception) {
            // Skip projects that can't be processed
        }
    }

    return dependentProjects
}

fun Project.printDependentExtensions() {
    printDependentExtensions(mutableSetOf())
}

private fun Project.printDependentExtensions(visited: MutableSet<String>) {
    if (path in visited) return
    visited.add(path)

    getDependents().forEach { project ->
        when {
            project.path.startsWith(":src:") ->
                println(project.path)

            project.path.startsWith(":lib-multisrc:") ->
                project.getDependents().forEach { println(it.path) }

            project.path.startsWith(":lib:") ->
                project.printDependentExtensions(visited)
        }
    }
}

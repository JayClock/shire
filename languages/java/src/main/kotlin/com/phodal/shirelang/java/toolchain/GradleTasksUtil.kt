package com.phodal.shirelang.java.toolchain

import com.intellij.execution.RunManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.service.ui.completion.TextCompletionInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.phodal.shirelang.java.impl.GRADLE_COMPLETION_COMPARATOR
import org.jetbrains.plugins.gradle.service.execution.GradleExternalTaskConfigurationType
import org.jetbrains.plugins.gradle.service.execution.GradleRunConfiguration
import org.jetbrains.plugins.gradle.service.project.GradleTasksIndices
import org.jetbrains.plugins.gradle.util.GradleConstants

object GradleTasksUtil {
    fun collectGradleTasksWithCheck(project: Project): List<TextCompletionInfo> {
        val projectDataManager = ProjectDataManager.getInstance()
        val projectsData = projectDataManager.getExternalProjectsData(project, GradleConstants.SYSTEM_ID)

        return if (projectsData.isNotEmpty()) {
            collectGradleTasks(project)
        } else {
            emptyList()
        }
    }

    /**
     * Check start java task name, like:
     * - Spring Boot: `bootRun`
     * - Quarkus: `quarkusDev`
     * - Micronaut: `run`
     * - Helidon: `run`
     */
    fun getRunTaskName(project: Project): String {
        val tasks = collectGradleTasks(project)
        val runTasks = tasks.filter { it.text.contains("run", ignoreCase = true) }
        if (runTasks.isNotEmpty()) {
            return runTasks.first().text
        }

        return "run"
    }

    fun collectGradleTasks(project: Project): List<TextCompletionInfo> {
        val indices = GradleTasksIndices.getInstance(project)

        val tasks = indices.findTasks(project.guessProjectDir()!!.path)
            .filterNot { it.isInherited }
            .groupBy { it.name }
            .map { TextCompletionInfo(it.key, it.value.first().description) }
            .sortedWith(Comparator.comparing({ it.text }, GRADLE_COMPLETION_COMPARATOR))
        return tasks
    }

    fun createGradleTestConfiguration(virtualFile: VirtualFile, project: Project): GradleRunConfiguration? {
        val name = virtualFile.name

        val canonicalName = runReadAction {
            val psiFile: PsiJavaFile =
                PsiManager.getInstance(project).findFile(virtualFile) as? PsiJavaFile ?: return@runReadAction null
            // skip for non-test files
            (psiFile.packageName + "." + virtualFile.nameWithoutExtension).removePrefix(".")
        } ?: return null

        val runManager = RunManager.getInstance(project)

        var moduleName = ""
        val moduleForFile = runReadAction { ProjectFileIndex.getInstance(project).getModuleForFile(virtualFile) }
        // a moduleForFile.name will be like <project>.<module>.<testModule>, so we need to remove the last part and first part
        if (moduleForFile != null) {
            val moduleNameSplit = moduleForFile.name.split(".").drop(1).dropLast(1).joinToString(":")
            if (moduleNameSplit.isNotEmpty()) {
                moduleName = "$moduleNameSplit:"
            }
        }

        // todo: add maven ??
        val configuration = runManager.createConfiguration(name, GradleExternalTaskConfigurationType::class.java)
        val runConfiguration = configuration.configuration as GradleRunConfiguration

        runConfiguration.isDebugServerProcess = false
        runConfiguration.settings.externalProjectPath = project.guessProjectDir()?.path
        // todo: add module for test
        runConfiguration.rawCommandLine = moduleName + "test --tests \"${canonicalName}\""

        runManager.addConfiguration(configuration)
        runManager.selectedConfiguration = configuration

        return runConfiguration
    }

    /**
     * This function is used to create a Gradle run configuration for a specific virtual file in a project.
     * It takes the virtual file, project, and task name as parameters and returns a GradleRunConfiguration object.
     *
     * @param virtualFile The virtual file for which the configuration is being created.
     * @param project The project in which the configuration is being created.
     * @param taskName The name of the task to be executed in the Gradle run configuration.
     * @return A GradleRunConfiguration object representing the created configuration.
     */
    fun createConfigForGradle(project: Project, taskName: String): GradleRunConfiguration {
        val runManager = RunManager.getInstance(project)
        val configuration = runManager.createConfiguration(
            taskName,
            GradleExternalTaskConfigurationType::class.java
        )
        val runConfiguration = configuration.configuration as GradleRunConfiguration

        runConfiguration.isDebugServerProcess = false
        runConfiguration.settings.externalProjectPath = project.guessProjectDir()?.path

        runConfiguration.rawCommandLine = taskName

        runManager.addConfiguration(configuration)
        runManager.selectedConfiguration = configuration

        return runConfiguration
    }
}

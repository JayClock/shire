package com.phodal.shirelang.java.impl

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.util.SmartList
import com.phodal.shirecore.provider.ProjectRunService
import com.phodal.shirelang.java.toolchain.GradleTasksUtil
import icons.GradleIcons

class JavaRunProjectService : ProjectRunService {
    override fun isAvailable(project: Project): Boolean {
        return ProjectRootManager.getInstance(project).projectSdk is JavaSdk
    }

    override fun run(project: Project, taskName: String) {
//        val configureGradleTask = GradleTasksUtil.createConfigForGradle(project, taskName)
    }

    override fun lookupAvailableTask(
        project: Project,
        parameters: CompletionParameters,
        result: CompletionResultSet,
    ): List<LookupElement> {
        val lookupElements: MutableList<LookupElement> = SmartList()
        GradleTasksUtil.collectGradleTasksWithCheck(project).forEach {
            val element = LookupElementBuilder.create(it.text)
                .withTypeText(it.description)
                .withIcon(GradleIcons.Gradle)
            result.addElement(element)
        }

        return lookupElements
    }
}

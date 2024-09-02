package com.phodal.shirecore.provider.variable

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.phodal.shirecore.provider.variable.model.ToolchainVariable

interface ToolchainVariableProvider : VariableProvider<ToolchainVariable> {
    fun isResolvable(variable: ToolchainVariable, psiElement: PsiElement?, project: Project): Boolean

    companion object {
        private val EP_NAME: ExtensionPointName<ToolchainVariableProvider> =
            ExtensionPointName("com.phodal.shireToolchainVariableProvider")

        fun all(): List<ToolchainVariableProvider> {
            return EP_NAME.extensionList
        }

        fun provide(variable: ToolchainVariable, element: PsiElement?, project: Project): ToolchainVariableProvider? {
            return EP_NAME.extensionList.firstOrNull {
                it.isResolvable(variable, element, project)
            }
        }
    }
}

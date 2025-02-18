// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.k2.codeinsight.intentions

import com.intellij.codeInsight.intention.LowPriorityAction
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModPsiUpdater
import com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.annotations.hasAnnotation
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.idea.codeinsight.api.applicable.asUnit
import org.jetbrains.kotlin.idea.codeinsight.api.applicable.intentions.KotlinApplicableModCommandAction
import org.jetbrains.kotlin.idea.codeinsight.api.applicators.ApplicabilityRange
import org.jetbrains.kotlin.idea.codeinsights.impl.base.intentions.AddAccessorUtils
import org.jetbrains.kotlin.idea.codeinsights.impl.base.intentions.AddAccessorUtils.addAccessors
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.containingClass
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.hasExpectModifier

internal abstract class AbstractAddAccessorIntention(
    private val addGetter: Boolean,
    private val addSetter: Boolean,
) : KotlinApplicableModCommandAction<KtProperty, Unit>(KtProperty::class) {

    override fun getFamilyName(): String = AddAccessorUtils.familyAndActionName(addGetter, addSetter)

    override fun getApplicableRanges(element: KtProperty): List<TextRange> =
        ApplicabilityRange.single(element) { property ->
            if (property.hasInitializer()) property.nameIdentifier
            else property
        }

    override fun isApplicableByPsi(element: KtProperty): Boolean {
        if (element.isLocal ||
            element.hasDelegate() ||
            element.containingClass()?.isInterface() == true ||
            element.containingClassOrObject?.hasExpectModifier() == true ||
            element.hasModifier(KtTokens.ABSTRACT_KEYWORD) ||
            element.hasModifier(KtTokens.LATEINIT_KEYWORD) ||
            element.hasModifier(KtTokens.CONST_KEYWORD)
        ) {
            return false
        }

        if (element.typeReference == null && !element.hasInitializer()) return false
        if (addSetter && (!element.isVar || element.setter != null)) return false
        if (addGetter && element.getter != null) return false

        return true
    }

    context(KaSession)
    override fun prepareContext(element: KtProperty): Unit? {
        if (element.annotationEntries.isEmpty()) return Unit
        val symbol = element.getVariableSymbol() as? KaPropertySymbol ?: return null

        val isApplicable = symbol.backingFieldSymbol
            ?.hasAnnotation(JVM_FIELD_CLASS_ID) != true
        return isApplicable.asUnit
    }

    override fun invoke(
      actionContext: ActionContext,
      element: KtProperty,
      elementContext: Unit,
      updater: ModPsiUpdater,
    ) {
        addAccessors(element, addGetter, addSetter, updater::moveCaretTo)
    }
}

private val JVM_FIELD_CLASS_ID = ClassId.topLevel(JvmAbi.JVM_FIELD_ANNOTATION_FQ_NAME)

internal class AddPropertyAccessorsIntention : AbstractAddAccessorIntention(addGetter = true, addSetter = true), LowPriorityAction
internal class AddPropertyGetterIntention : AbstractAddAccessorIntention(addGetter = true, addSetter = false)
internal class AddPropertySetterIntention : AbstractAddAccessorIntention(addGetter = false, addSetter = true)
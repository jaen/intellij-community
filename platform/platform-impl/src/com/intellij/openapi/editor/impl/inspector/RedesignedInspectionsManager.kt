// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.inspector

import com.intellij.openapi.application.Experiments
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
class RedesignedInspectionsManager {
  companion object {
    @JvmStatic
    fun isAvailable(): Boolean {
      return Experiments.getInstance().isFeatureEnabled("ide.redesigned.inspector")
    }
  }
}
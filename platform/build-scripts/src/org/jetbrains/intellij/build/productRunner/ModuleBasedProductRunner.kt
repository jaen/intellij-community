// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.intellij.build.productRunner

import com.intellij.openapi.util.SystemInfoRt
import com.intellij.platform.runtime.repository.RuntimeModuleId
import org.jetbrains.intellij.build.BuildContext
import org.jetbrains.intellij.build.OsFamily
import org.jetbrains.intellij.build.VmProperties
import org.jetbrains.intellij.build.impl.VmOptionsGenerator
import kotlin.io.path.pathString
import kotlin.time.Duration

/**
 * Runs the product using the module-based loader which will take class-files from module output directories.
 */
internal class ModuleBasedProductRunner(private val rootModuleForModularLoader: String, private val context: BuildContext) : IntellijProductRunner {
  override suspend fun runProduct(args: List<String>, additionalVmProperties: VmProperties, timeout: Duration) {
    val systemProperties = VmProperties(
      mapOf(
        "intellij.platform.runtime.repository.path" to context.originalModuleRepository.repositoryPath.pathString,
        "intellij.platform.root.module" to rootModuleForModularLoader,
        "intellij.platform.product.mode" to context.productProperties.productMode.id,
        "idea.vendor.name" to context.applicationInfo.shortCompanyName,
      )
    )

    val loaderModule = context.originalModuleRepository.repository.getModule(RuntimeModuleId.module("intellij.platform.runtime.loader"))
    val ideClasspath = loaderModule.moduleClasspath.map { it.pathString }
    val osFamily = when {
      SystemInfoRt.isWindows -> OsFamily.WINDOWS
      SystemInfoRt.isMac -> OsFamily.MACOS
      else -> OsFamily.LINUX
    }
    runApplicationStarter(
      context = context,
      classpath = ideClasspath,
      args = args,
      vmProperties = systemProperties + additionalVmProperties,
      vmOptions = VmOptionsGenerator.computeVmOptions(context, osFamily) +
                  //we need to unset 'jna.nounpack' (see IJ-CR-125211), otherwise the process will fail to load JNA on macOS (IJPL-150094)
                  context.productProperties.additionalIdeJvmArguments.filterNot { it == "-Djna.nounpack=true" } +
                  context.productProperties.getAdditionalContextDependentIdeJvmArguments(context),
    )
  }
}
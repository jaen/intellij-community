// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.application

import com.intellij.ide.plugins.IdeaPluginDependency
import com.intellij.ide.plugins.IdeaPluginDescriptorImpl
import com.intellij.ide.plugins.PluginInstaller
import com.intellij.ide.plugins.PluginLoadingResult
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.plugins.isBrokenPlugin
import com.intellij.ide.plugins.loadDescriptorFromArtifact
import com.intellij.ide.plugins.loadDescriptors
import com.intellij.openapi.application.PluginAutoUpdateRepository.PluginUpdateInfo
import com.intellij.openapi.application.PluginAutoUpdateRepository.clearUpdates
import com.intellij.openapi.application.PluginAutoUpdateRepository.getAutoUpdateDirPath
import com.intellij.openapi.application.PluginAutoUpdateRepository.safeConsumeUpdates
import com.intellij.openapi.application.impl.ApplicationInfoImpl
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.getOrLogException
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.updateSettings.impl.PluginDownloader
import com.intellij.platform.diagnostic.telemetry.impl.span
import com.intellij.platform.ide.bootstrap.ZipFilePoolImpl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.jetbrains.annotations.ApiStatus
import java.nio.file.Path
import kotlin.Result
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.forEach
import kotlin.io.path.exists

@ApiStatus.Internal
object PluginAutoUpdater {
  @Volatile
  private var pluginAutoUpdateResult: Result<PluginAutoUpdateStatistics>? = null

  /**
   * This method is called during startup, before the plugins are loaded.
   */
  suspend fun applyPluginUpdates(logDeferred: Deferred<Logger>) {
    val updates = safeConsumeUpdates(logDeferred).filter { (_, info) ->
      runCatching {
        Path.of(info.pluginPath).exists() && getAutoUpdateDirPath().resolve(info.updateFilename).exists()
      }.getOrElse { e ->
        logDeferred.await().warn(e)
        false
      }
    }
    pluginAutoUpdateResult = runCatching {
      val updatesApplied = applyPluginUpdates(updates, logDeferred)
      PluginAutoUpdateStatistics(updatesPrepared = updates.size, pluginsUpdated = updatesApplied)
    }.apply {
      getOrLogException { e ->
        logDeferred.await().error("Error occurred during application of plugin updates", e)
      }
    }
    runCatching {
      clearUpdates()
    }.getOrLogException { e ->
      logDeferred.await().warn("Failed to clear plugin auto update directory", e)
    }
  }

  /**
   * @return number of successfully applied updates
   */
  private suspend fun applyPluginUpdates(updates: Map<PluginId, PluginUpdateInfo>, logDeferred: Deferred<Logger>): Int {
    if (updates.isEmpty()) {
      return 0
    }
    logDeferred.await().info("There are ${updates.size} prepared updates for plugins. Applying...")
    val autoupdatesDir = getAutoUpdateDirPath()

    val currentDescriptors = span("loading existing descriptors") {
      val pool = ZipFilePoolImpl()
      val result = loadDescriptors(
        CompletableDeferred(pool),
        CompletableDeferred(PluginAutoUpdateRepository::class.java.classLoader)
      )
      pool.clear()
      result.second
    }
    // shadowing intended
    val updates = updates.filter { (id, _) ->
      (!PluginManagerCore.isDisabled(id) && (currentDescriptors.getIdMap()[id] != null || currentDescriptors.getIncompleteIdMap()[id] != null))
        .also { pluginForUpdateExists ->
          if (!pluginForUpdateExists) logDeferred.await().warn("Update for plugin $id is declined since the plugin is not going to be loaded")
        }
    }
    val updateDescriptors = span("update descriptors loading") {
      updates.mapValues { (_, info) ->
        val updateFile = autoupdatesDir.resolve(info.updateFilename)
        async(Dispatchers.IO) {
          runCatching { loadDescriptorFromArtifact(updateFile, null) }
        }
      }.mapValues { it.value.await() }
    }.filter {
      (it.value.getOrNull() != null).also { loaded ->
        if (!loaded) logDeferred.await().warn("Update for plugin ${it.key} has failed to load", it.value.exceptionOrNull())
      }
    }.mapValues { it.value.getOrNull()!! }

    val updateCheck = determineValidUpdates(currentDescriptors, updateDescriptors)
    updateCheck.rejectedUpdates.forEach { (id, reason) ->
      logDeferred.await().warn("Update for plugin $id has been rejected: $reason")
    }
    var updatesApplied = 0
    for (id in updateCheck.updatesToApply) {
      val update = updates[id]!!
      runCatching {
        PluginInstaller.unpackPlugin(getAutoUpdateDirPath().resolve(update.updateFilename), PathManager.getPluginsDir())
      }.onFailure {
        logDeferred.await().warn("Failed to apply update for plugin $id", it)
      }.onSuccess {
        logDeferred.await().info("Plugin $id has been successfully updated: " +
                                 "version ${currentDescriptors.getIdMap()[id]?.version} -> ${updateDescriptors[id]!!.version}")
        updatesApplied++
      }
    }
    return updatesApplied
  }

  private data class UpdateCheckResult(
    val updatesToApply: Set<PluginId>,
    val rejectedUpdates: Map<PluginId, String>,
  )

  private fun determineValidUpdates(
    currentDescriptors: PluginLoadingResult,
    updates: Map<PluginId, IdeaPluginDescriptorImpl>,
  ): UpdateCheckResult {
    val updatesToApply = mutableSetOf<PluginId>()
    val rejectedUpdates = mutableMapOf<PluginId, String>()
    // checks mostly duplicate what is written in com.intellij.ide.plugins.PluginInstaller.installFromDisk. FIXME, I guess
    for ((id, updateDesc) in updates) {
      val existingDesc = currentDescriptors.getIdMap()[id] ?: currentDescriptors.getIncompleteIdMap()[id]
      if (existingDesc == null) {
        rejectedUpdates[id] = "plugin $id is not installed"
        continue
      }
      // no third-party plugin check, settings are not available at this point; that check must be done when downloading the updates
      if (PluginManagerCore.isIncompatible(updateDesc)) {
        rejectedUpdates[id] = "plugin $id of version ${updateDesc.version} is not compatible with current IDE build"
        continue
      }
      if (isBrokenPlugin(updateDesc)) {
        rejectedUpdates[id] = "plugin $id of version ${updateDesc.version} is known to be broken"
        continue
      }
      if (ApplicationInfoImpl.getShadowInstance().isEssentialPlugin(id)) {
        rejectedUpdates[id] = "plugin $id is part of the IDE distribution and cannot be updated without IDE update"
        continue
      }
      if (PluginDownloader.compareVersionsSkipBrokenAndIncompatible(updateDesc.version, existingDesc) <= 0) {
        rejectedUpdates[id] = "plugin $id has same or newer version installed (${existingDesc.version} vs update version ${updateDesc.version})"
        continue
      }
      val unmetDependencies = findUnmetDependencies(updateDesc, currentDescriptors)
      if (unmetDependencies.isNotEmpty()) {
        rejectedUpdates[id] = "plugin $id of version ${updateDesc.version} has unmet dependencies " +
                              "(plugin ids): ${unmetDependencies.joinToString(", ") { it.pluginId.idString }}"
        continue
      }
      // TODO check signature ? com.intellij.ide.plugins.marketplace.PluginSignatureChecker; probably also should be done after download
      updatesToApply.add(id)
    }
    return UpdateCheckResult(updatesToApply, rejectedUpdates)
  }

  // TODO such functionality must be extracted into a single place com.intellij.ide.plugins.PluginInstaller.findNotInstalledPluginDependencies
  /**
   * returns a list of unmet dependencies
   */
  private fun findUnmetDependencies(
    updateDescriptor: IdeaPluginDescriptorImpl,
    currentDescriptors: PluginLoadingResult,
  ): List<IdeaPluginDependency> {
    return updateDescriptor.pluginDependencies.filter { dep ->
      if (dep.isOptional) return@filter false
      // TODO should we check module dependencies too?
      // TODO revise if incomplete is fine
      val suchPluginExists = currentDescriptors.getIdMap().containsKey(dep.pluginId) ||
                             currentDescriptors.getIncompleteIdMap().containsKey(dep.pluginId)
      !suchPluginExists
    }
  }

  /**
   * @return not null if plugin auto update was triggered
   */
  fun getPluginAutoUpdateResult(): Result<PluginAutoUpdateStatistics>? = pluginAutoUpdateResult

  data class PluginAutoUpdateStatistics(
    val updatesPrepared: Int,
    val pluginsUpdated: Int,
  )
}
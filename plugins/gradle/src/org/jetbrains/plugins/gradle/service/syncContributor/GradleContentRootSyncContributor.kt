// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gradle.service.syncContributor

import com.intellij.gradle.toolingExtension.modelAction.GradleModelFetchPhase
import com.intellij.openapi.externalSystem.service.project.nameGenerator.ModuleNameGenerator
import com.intellij.openapi.externalSystem.util.Order
import com.intellij.openapi.progress.checkCanceled
import com.intellij.platform.backend.workspace.workspaceModel
import com.intellij.platform.workspace.jps.entities.*
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.WorkspaceEntity
import com.intellij.platform.workspace.storage.entities
import com.intellij.platform.workspace.storage.impl.url.toVirtualFileUrl
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.plugins.gradle.model.GradleLightBuild
import org.jetbrains.plugins.gradle.model.GradleLightProject
import org.jetbrains.plugins.gradle.service.project.ProjectResolverContext
import org.jetbrains.plugins.gradle.service.syncAction.GradleSyncContributor
import org.jetbrains.plugins.gradle.service.syncAction.GradleSyncProjectConfigurator.project
import org.jetbrains.plugins.gradle.service.syncContributor.entitites.GradleBuildEntitySource
import org.jetbrains.plugins.gradle.service.syncContributor.entitites.GradleLinkedProjectEntitySource
import org.jetbrains.plugins.gradle.service.syncContributor.entitites.GradleProjectEntitySource
import java.nio.file.Path

@ApiStatus.Internal
@Order(GradleSyncContributor.Order.CONTENT_ROOT_CONTRIBUTOR)
class GradleContentRootSyncContributor : GradleSyncContributor {

  override val name: String = "Gradle Content Root"

  override suspend fun onModelFetchPhaseCompleted(
    context: ProjectResolverContext,
    storage: MutableEntityStorage,
    phase: GradleModelFetchPhase
  ) {
    if (context.isPhasedSyncEnabled) {
      if (phase == GradleModelFetchPhase.PROJECT_LOADED_PHASE) {
        configureProjectContentRoots(context, storage)
      }
    }
  }

  private suspend fun configureProjectContentRoots(
    context: ProjectResolverContext,
    storage: MutableEntityStorage
  ) {
    val project = context.project()
    val virtualFileUrlManager = project.workspaceModel.getVirtualFileUrlManager()

    val contentRootsToAdd = LinkedHashMap<GradleProjectEntitySource, GradleContentRootData>()

    val contentRootEntities = storage.entities<ContentRootEntity>()

    val linkedProjectRootPath = Path.of(context.projectPath)
    val linkedProjectRootUrl = linkedProjectRootPath.toVirtualFileUrl(virtualFileUrlManager)
    val linkedProjectEntitySource = GradleLinkedProjectEntitySource(linkedProjectRootUrl)

    for (buildModel in context.allBuilds) {

      val buildRootPath = buildModel.buildIdentifier.rootDir.toPath()
      val buildRootUrl = buildRootPath.toVirtualFileUrl(virtualFileUrlManager)
      val buildEntitySource = GradleBuildEntitySource(linkedProjectEntitySource, buildRootUrl)

      for (projectModel in buildModel.projects) {

        checkCanceled()

        val projectRootPath = projectModel.projectDirectory.toPath()
        val projectRootUrl = projectRootPath.toVirtualFileUrl(virtualFileUrlManager)
        val projectEntitySource = GradleProjectEntitySource(buildEntitySource, projectRootUrl)

        val contentRootData = GradleContentRootData(buildModel, projectModel, projectEntitySource)

        if (contentRootEntities.all { !isConflictedContentRootEntity(it, contentRootData) }) {
          contentRootsToAdd[projectEntitySource] = contentRootData
        }
      }
    }

    for (contentRootData in contentRootsToAdd.values) {

      checkCanceled()

      configureContentRoot(context, storage, contentRootData)
    }
  }

  private fun isConflictedContentRootEntity(
    contentRootEntity: ContentRootEntity,
    contentRootData: GradleContentRootData,
  ): Boolean {
    val entitySource = contentRootData.entitySource
    return contentRootEntity.entitySource == entitySource ||
           contentRootEntity.url == entitySource.projectRootUrl
  }

  private fun configureContentRoot(
    context: ProjectResolverContext,
    storage: MutableEntityStorage,
    contentRootData: GradleContentRootData,
  ): WorkspaceEntity.Builder<*> {
    val moduleEntity = addModuleEntity(context, storage, contentRootData)
    addContentRootEntity(storage, contentRootData, moduleEntity)
    return moduleEntity
  }

  private fun addModuleEntity(
    context: ProjectResolverContext,
    storage: MutableEntityStorage,
    contentRootData: GradleContentRootData,
  ): ModuleEntity.Builder {
    val entitySource = contentRootData.entitySource
    val moduleName = resolveUniqueModuleName(context, storage, contentRootData)
    val moduleEntity = ModuleEntity(
      name = moduleName,
      entitySource = entitySource,
      dependencies = emptyList()
    )
    storage addEntity moduleEntity
    return moduleEntity
  }

  private fun addContentRootEntity(
    storage: MutableEntityStorage,
    contentRootData: GradleContentRootData,
    moduleEntity: ModuleEntity.Builder,
  ) {
    val entitySource = contentRootData.entitySource
    storage addEntity ContentRootEntity(
      url = entitySource.projectRootUrl,
      entitySource = entitySource,
      excludedPatterns = emptyList()
    ) {
      module = moduleEntity
    }
  }

  private fun resolveUniqueModuleName(
    context: ProjectResolverContext,
    storage: EntityStorage,
    contentRootData: GradleContentRootData,
  ): String {
    for (moduleNameCandidate in generateModuleNames(context, contentRootData)) {
      val moduleId = ModuleId(moduleNameCandidate)
      if (storage.resolve(moduleId) == null) {
        return moduleNameCandidate
      }
    }
    throw IllegalStateException("Too many duplicated module names")
  }

  private fun generateModuleNames(
    context: ProjectResolverContext,
    contentRootData: GradleContentRootData,
  ): Iterable<String> {
    val buildModel = contentRootData.buildModel
    val projectModel = contentRootData.projectModel
    val moduleName = resolveModuleName(context, buildModel, projectModel)
    val modulePath = projectModel.projectDirectory.toPath()
    return ModuleNameGenerator.generate(null, moduleName, modulePath, ".")
  }

  private fun resolveModuleName(
    context: ProjectResolverContext,
    buildModel: GradleLightBuild,
    projectModel: GradleLightProject,
  ): String {
    val moduleName = resolveGradleProjectQualifiedName(buildModel, projectModel)
    val buildSrcGroup = context.getBuildSrcGroup(buildModel.name, buildModel.buildIdentifier)
    if (buildSrcGroup.isNullOrBlank()) {
      return moduleName
    }
    return "$buildSrcGroup.$moduleName"
  }

  private fun resolveGradleProjectQualifiedName(
    buildModel: GradleLightBuild,
    projectModel: GradleLightProject,
  ): String {
    if (projectModel.path == ":") {
      return buildModel.name
    }
    if (projectModel.path.startsWith(":")) {
      return buildModel.name + projectModel.path.replace(":", ".")
    }
    return projectModel.path.replace(":", ".")
  }

  private class GradleContentRootData(
    val buildModel: GradleLightBuild,
    val projectModel: GradleLightProject,
    val entitySource: GradleProjectEntitySource,
  )
}
/*
 * Copyright (C) 2023 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.cash.better.dynamic.features

import app.cash.better.dynamic.features.codegen.AndroidVariant
import app.cash.better.dynamic.features.codegen.TypesafeImplementationsCompilationTask
import app.cash.better.dynamic.features.codegen.TypesafeImplementationsGeneratorTask
import app.cash.better.dynamic.features.codegen.api.KSP_REPORT_DIRECTORY_PREFIX
import app.cash.better.dynamic.features.tasks.BaseLockfileWriterTask
import app.cash.better.dynamic.features.tasks.CheckExternalResourcesTask
import app.cash.better.dynamic.features.tasks.CheckLockfileTask
import app.cash.better.dynamic.features.tasks.DependencyGraphWriterTask
import app.cash.better.dynamic.features.tasks.DependencyGraphWriterTask.ResolvedComponentResultPair
import app.cash.better.dynamic.features.tasks.GenerateExternalResourcesTask
import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.attributes.BuildTypeAttr
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import com.android.build.api.variant.Variant
import com.android.build.gradle.internal.publishing.AndroidArtifacts
import com.android.build.gradle.internal.res.LinkApplicationAndroidResourcesTask
import com.android.build.gradle.internal.tasks.factory.dependsOn
import com.google.devtools.ksp.gradle.KspTask
import com.google.devtools.ksp.gradle.KspTaskJvm
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Usage
import org.gradle.api.file.Directory
import org.gradle.api.tasks.TaskProvider
import org.gradle.configurationcache.extensions.capitalized
import org.gradle.process.CommandLineArgumentProvider

@Suppress("UnstableApiUsage")
class BetterDynamicFeaturesPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    check(project.plugins.hasPlugin("com.android.application") || project.plugins.hasPlugin("com.android.dynamic-feature")) {
      "Plugin 'com.android.application' or 'com.android.dynamic-feature' must also be applied before this plugin"
    }

    val hasLockfileStartTask = project.gradle.startParameter.taskNames.any {
      it.contains(
        "writeLockfile",
        ignoreCase = true,
      ) || it.contains(WRITE_DEPENDENCY_GRAPH_REGEX)
    }
    val startTaskCount = project.gradle.startParameter.taskNames.count()
    require(!hasLockfileStartTask || startTaskCount == 1) { "Updating the lockfile and running other tasks together is an error. Update the lockfile first, and then run your other tasks separately." }

    if (project.plugins.hasPlugin("com.android.application")) {
      applyToApplication(project)
    } else {
      applyToFeature(project)
    }
  }

  private fun applyToFeature(project: Project) {
    val pluginExtension = project.extensions.create(
      "betterDynamicFeatures",
      BetterDynamicFeaturesFeatureExtension::class.java,
    )
    val androidComponents = project.extensions.getByType(AndroidComponentsExtension::class.java)
    val sharedConfiguration = project.createSharedFeatureConfiguration()

    project.setupFeatureDependencyGraphTasks(androidComponents, sharedConfiguration)
    project.plugins.withId("com.google.devtools.ksp") {
      project.dependencies.add("implementation", "app.cash.better.dynamic.features:runtime:$VERSION")
      project.setupFeatureKsp(androidComponents)
    }
  }

  private fun applyToApplication(project: Project) {
    val pluginExtension =
      project.extensions.create("betterDynamicFeatures", BetterDynamicFeaturesExtension::class.java)

    val androidComponents = project.extensions.getByType(AndroidComponentsExtension::class.java)
    val sharedConfiguration = project.createSharedBaseConfiguration()

    project.plugins.withId("com.google.devtools.ksp") {
      project.dependencies.add("implementation", "app.cash.better.dynamic.features:runtime:$VERSION")
      project.setupBaseCodegen(androidComponents)
    }

    androidComponents.finalizeDsl { extension ->
      check(extension is ApplicationExtension)
      val featureProjects = extension.dynamicFeatures.map { project.project(it) }
      featureProjects.forEach {
        project.dependencies.add(CONFIGURATION_BDF, it)
        if (project.plugins.hasPlugin("com.google.devtools.ksp")) {
          project.dependencies.add(CONFIGURATION_BDF_IMPLEMENTATIONS, it)
        }
      }

      project.setupBaseDependencyGraphTasks(androidComponents, featureProjects, sharedConfiguration)
    }

    androidComponents.onVariants { variant ->
      // We only want to enforce the lockfile if we aren't explicitly trying to update it
      if (project.gradle.startParameter.taskNames.none {
        it.contains("writeLockfile", ignoreCase = true) ||
          it.contains(WRITE_DEPENDENCY_GRAPH_REGEX) ||
          it.contains("checkLockfile", ignoreCase = true)
      }
      ) {
        project.configurations.named("${variant.name}RuntimeClasspath").configure {
          it.resolutionStrategy.activateDependencyLocking()
        }
        project.configurations.named("${variant.name}CompileClasspath").configure {
          it.resolutionStrategy.activateDependencyLocking()
        }
      }
    }

    project.setupBaseResourcesCheckingTasks(androidComponents, pluginExtension)
  }

  private fun Configuration.getConfigurationArtifactCollection(): ArtifactCollection =
    incoming.artifactView { config ->
      config.attributes { container ->
        container.attribute(
          AndroidArtifacts.ARTIFACT_TYPE,
          AndroidArtifacts.ArtifactType.AAR_OR_JAR.type,
        )
      }

      // Look only for external dependencies
      config.componentFilter { it !is ProjectComponentIdentifier }
    }.artifacts

  private fun Project.configureDependencyGraphTask(variant: Variant): TaskProvider<DependencyGraphWriterTask> =
    tasks.register(
      "write${variant.name.capitalized()}DependencyGraph",
      DependencyGraphWriterTask::class.java,
    ) { task ->
      val artifactCollections = project.configurations.getByName("${variant.name}RuntimeClasspath")
        .getConfigurationArtifactCollection()

      task.dependencyFileCollection.setFrom(artifactCollections.artifactFiles)

      task.setResolvedLockfileEntriesProvider(
        project.provider {
          val runtime = project.configurations.getByName("${variant.name}RuntimeClasspath")
            .incoming
            .resolutionResult
            .root

          val compile = project.configurations.getByName("${variant.name}CompileClasspath")
            .incoming
            .resolutionResult
            .root

          ResolvedComponentResultPair(runtime, compile)
        },
        variant.name,
      )

      task.partialLockFile.set { buildDir.resolve("betterDynamicFeatures/deps/${variant.name}DependencyGraph.json") }
      task.group = GROUP
    }

  private fun Project.createSharedFeatureConfiguration(): Configuration =
    configurations.create(CONFIGURATION_BDF).apply {
      isCanBeConsumed = true
      isCanBeResolved = false
      isVisible = false

      attributes.apply {
        attribute(
          Usage.USAGE_ATTRIBUTE,
          project.objects.named(Usage::class.java, ATTRIBUTE_USAGE_METADATA),
        )
      }
      outgoing.variants.create(VARIANT_DEPENDENCY_GRAPHS)
    }

  private fun Project.createSharedBaseConfiguration(): Configuration =
    configurations.create(CONFIGURATION_BDF).apply {
      isCanBeConsumed = true
      isCanBeResolved = true
      isVisible = false

      attributes.apply {
        attribute(
          Usage.USAGE_ATTRIBUTE,
          project.objects.named(Usage::class.java, ATTRIBUTE_USAGE_METADATA),
        )
      }
      outgoing.variants.create(VARIANT_DEPENDENCY_GRAPHS)
    }

  private fun Project.setupFeatureDependencyGraphTasks(
    androidComponents: AndroidComponentsExtension<*, *, *>,
    configuration: Configuration,
  ) {
    androidComponents.onVariants { variant ->
      val task = configureDependencyGraphTask(variant)
      configuration.outgoing.variants.getByName(VARIANT_DEPENDENCY_GRAPHS) { configurationVariant ->
        configurationVariant.artifact(task.flatMap { it.partialLockFile }) {
          it.builtBy(task)
          it.type = ARTIFACT_TYPE_FEATURE_DEPENDENCY_GRAPH
        }
      }
    }
  }

  private fun Project.setupBaseDependencyGraphTasks(
    androidComponents: AndroidComponentsExtension<*, *, *>,
    featureProjects: List<Project>,
    configuration: Configuration,
  ) {
    featureProjects.forEach { dependencies.add(CONFIGURATION_BDF, it) }
    // This allows us to access artifacts created in this project's tasks via the artifactView APIs
    dependencies.add(CONFIGURATION_BDF, this)

    androidComponents.onVariants { variant ->
      val task = configureDependencyGraphTask(variant)
      configuration.outgoing.variants.getByName(VARIANT_DEPENDENCY_GRAPHS) { configurationVariant ->
        configurationVariant.artifact(task.flatMap { it.partialLockFile }) {
          it.builtBy(task)
          it.type = ARTIFACT_TYPE_BASE_DEPENDENCY_GRAPH
        }
      }
    }

    val featureDependencyArtifacts = configuration.incoming.artifactView { config ->
      config.attributes { container ->
        container.attribute(ARTIFACT_TYPE, ARTIFACT_TYPE_FEATURE_DEPENDENCY_GRAPH)
      }
    }.artifacts.artifactFiles

    val baseDependencyArtifacts = configuration.incoming.artifactView { config ->
      config.attributes { container ->
        container.attribute(ARTIFACT_TYPE, ARTIFACT_TYPE_BASE_DEPENDENCY_GRAPH)
      }
    }.artifacts.artifactFiles

    project.tasks.register("writeLockfile", BaseLockfileWriterTask::class.java) { task ->
      task.outputLockfile.set(dependencyLocking.lockFile)
      task.featureDependencyGraphFiles.setFrom(featureDependencyArtifacts)
      task.baseDependencyGraphFiles.setFrom(baseDependencyArtifacts)

      task.group = GROUP
    }

    val checkLockfileTask =
      project.tasks.register("checkLockfile", CheckLockfileTask::class.java) { task ->
        task.currentLockfilePath.set(dependencyLocking.lockFile)
        task.outputFile.set { project.buildDir.resolve("betterDynamicFeatures/deps/lockfile_check") }
        task.featureDependencyGraphFiles.setFrom(featureDependencyArtifacts)
        task.baseDependencyGraphFiles.setFrom(baseDependencyArtifacts)

        task.group = GROUP
        task.projectPath.set(project.path)
      }
    tasks.named("preBuild").dependsOn(checkLockfileTask)
  }

  private fun Project.setupBaseResourcesCheckingTasks(
    androidComponents: AndroidComponentsExtension<*, *, *>,
    pluginExtension: BetterDynamicFeaturesExtension,
  ) {
    androidComponents.onVariants { variant ->
      val generateResourcesTask = tasks.register(
        "generate${variant.name.capitalized()}ExternalResources",
        GenerateExternalResourcesTask::class.java,
      ) { task ->
        task.outputDirectory.set(layout.buildDirectory.dir("betterDynamicFeatures/resources/${variant.name}"))
        task.declarations.set(provider { pluginExtension.externalStyles })
        task.group = GROUP
      }
      variant.sources.res?.addGeneratedSourceDirectory(
        generateResourcesTask,
        GenerateExternalResourcesTask::outputDirectory,
      )

      val checkExternalResourcesTask = tasks.register(
        "check${variant.name.capitalized()}ExternalResources",
        CheckExternalResourcesTask::class.java,
      ) { task ->
        val configuration = project.configurations.named("${variant.name}RuntimeClasspath")

        val artifactsProvider = configuration.map {
          it.incoming.artifactView { config ->
            config.attributes { container ->
              container.attribute(
                AndroidArtifacts.ARTIFACT_TYPE,
                AndroidArtifacts.ArtifactType.SYMBOL_LIST_WITH_PACKAGE_NAME.type,
              )
            }
          }.artifacts
        }

        task.setIncomingResources(artifactsProvider)
        task.incomingResourcesCollection.setFrom(artifactsProvider.map { it.artifactFiles })
        task.manifestFile.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        task.externalDeclarations.set(provider { pluginExtension.externalStyles })
        task.result.set(project.layout.buildDirectory.file("betterDynamicFeatures/resourcesCheck/${variant.name}/result.txt"))
        variant.sources.res?.all?.let { task.localResources.setFrom(it.map(List<Collection<Directory>>::flatten)) }

        task.group = GROUP
      }

      // Run the check task before the AGP `process[VariantName]Resources` task.
      tasks.withType(LinkApplicationAndroidResourcesTask::class.java).configureEach {
        if (it.name.contains(variant.name, ignoreCase = true)) {
          it.dependsOn(checkExternalResourcesTask)
        }
      }
    }
  }

  private fun taskName(vararg args: Any) = args.mapIndexed { index, arg ->
    when (arg) {
      is Variant -> if (index != 0) arg.name.replaceFirstChar { it.uppercase() } else arg.name
      else -> arg.toString()
    }
  }.joinToString(separator = "")

  private fun Project.setupFeatureKsp(
    androidComponents: AndroidComponentsExtension<*, *, *>,
  ) {
    val configuration = configurations.create(CONFIGURATION_BDF_IMPLEMENTATIONS).apply {
      isCanBeConsumed = true
      isCanBeResolved = false
      isVisible = false

      attributes.apply {
        attribute(
          Usage.USAGE_ATTRIBUTE,
          project.objects.named(Usage::class.java, ATTRIBUTE_USAGE_IMPLEMENTATIONS),
        )
      }
    }

    androidComponents.onVariants { androidVariant ->
      val reportDir =
        buildDir.resolve("betterDynamicFeatures/implementations/${androidVariant.name}")

      configuration.outgoing.variants.create(VARIANT_FEATURE_IMPLEMENTATION(androidVariant.name)) { variant ->
        variant.attributes.attribute(
          AndroidVariant.ANDROID_VARIANT_ATTRIBUTE,
          objects.named(AndroidVariant::class.java, androidVariant.name),
        )
        variant.artifact(project.layout.dir(project.provider { reportDir })) { artifact ->
          artifact.type = ARTIFACT_TYPE_FEATURE_IMPLEMENTATION_REPORT

          // TODO: Try and do this lazily
          tasks.withType(KspTaskJvm::class.java) { task ->
            if (!kspTaskMatchesVariant(task, androidVariant)) return@withType

            logger.debug("Configuring KSP task ${task.path} for variant ${androidVariant.name}")

            task.commandLineArgumentProviders.add(
              CommandLineArgumentProvider {
                listOf("$KSP_REPORT_DIRECTORY_PREFIX.${androidVariant.name}=${reportDir.absolutePath}")
              },
            )
            artifact.builtBy(task)
          }
        }
      }
    }
    dependencies.add("ksp", "app.cash.better.dynamic.features:codegen-ksp:$VERSION")
  }

  private fun Project.setupVariantCodegenDependencies(buildType: String): Configuration {
    val configurationName = CONFIGURATION_BDF_COMPILE_CLASSPATH(buildType)
    val compileConfiguration = configurations.findByName(configurationName)
      ?: run {
        configurations.create(configurationName).apply {
          isCanBeConsumed = false
          isCanBeResolved = true
          isVisible = false

          attributes.apply {
            attribute(BuildTypeAttr.ATTRIBUTE, project.objects.named(BuildTypeAttr::class.java, buildType))
            attribute(ARTIFACT_TYPE, "android-classes-jar")
          }
        }.also {
          dependencies.add(configurationName, "org.jetbrains.kotlin:kotlin-stdlib-jdk8:$KOTLIN_VERSION")
          project.dependencies.add(configurationName, "app.cash.better.dynamic.features:runtime:$VERSION")
        }
      }

    return compileConfiguration
  }

  private fun Project.setupBaseCodegen(
    androidComponents: AndroidComponentsExtension<*, *, *>,
  ) {
    val configuration = configurations.create(CONFIGURATION_BDF_IMPLEMENTATIONS).apply {
      isCanBeConsumed = false
      isCanBeResolved = true
      isVisible = false

      attributes.apply {
        attribute(
          Usage.USAGE_ATTRIBUTE,
          project.objects.named(Usage::class.java, ATTRIBUTE_USAGE_IMPLEMENTATIONS),
        )
      }
    }

    // Load the kotlin compiler into a configuration that can be loaded into the compilation work action
    val kotlinCompilerScope = configurations.create("kotlinCompilerScope")
    dependencies.add(
      kotlinCompilerScope.name,
      "org.jetbrains.kotlin:kotlin-compiler-embeddable:$KOTLIN_VERSION",
    )
    val resolvableKotlinCompilerConfiguration =
      configurations.create("resolvableKotlinCompilerScope") {
        it.extendsFrom(kotlinCompilerScope)
      }

    androidComponents.onVariants { androidVariant ->
      val implementationsTask = tasks.register(
        taskName("typesafe", androidVariant, "Implementations"),
        TypesafeImplementationsGeneratorTask::class.java,
      ) { task ->
        val featureReports = configuration.incoming.artifactView { config ->
          config.attributes { handler ->
            handler.attribute(ARTIFACT_TYPE, ARTIFACT_TYPE_FEATURE_IMPLEMENTATION_REPORT)
            handler.attribute(
              AndroidVariant.ANDROID_VARIANT_ATTRIBUTE,
              objects.named(AndroidVariant::class.java, androidVariant.name),
            )
          }
        }

        task.featureImplementationReports.setFrom(featureReports.artifacts.artifactFiles)
        task.generatedFilesDirectory.set(project.buildDir.resolve("betterDynamicFeatures/generatedImplementations/${androidVariant.name}"))
        task.generatedProguardFile.set(project.buildDir.resolve("betterDynamicFeatures/generatedProguard/betterDynamicFeatures-${androidVariant.name}.pro"))

        task.group = GROUP
      }
      androidVariant.proguardFiles.add(implementationsTask.flatMap { it.generatedProguardFile })

      val processTask = tasks.register(
        taskName("compile", androidVariant, "Implementations"),
        TypesafeImplementationsCompilationTask::class.java,
      ) { task ->
        task.generatedSources.set(implementationsTask.flatMap { it.generatedFilesDirectory })
        val compileConfiguration = setupVariantCodegenDependencies(androidVariant.buildType!!)
        task.compileClasspath.setFrom(project.provider { compileConfiguration.resolvedConfiguration.files })
        task.kotlinCompiler.from(resolvableKotlinCompilerConfiguration)
      }

      androidVariant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT)
        .use(processTask)
        .toTransform(
          ScopedArtifact.CLASSES,
          TypesafeImplementationsCompilationTask::projectJars,
          TypesafeImplementationsCompilationTask::projectClasses,
          TypesafeImplementationsCompilationTask::output,
        )
    }
  }

  private fun kspTaskMatchesVariant(task: KspTask, variant: Variant): Boolean {
    return task.name.contains(variant.name, ignoreCase = true) && !task.name.contains("UnitTest", ignoreCase = true)
  }

  companion object {
    internal const val GROUP = "Better Dynamic Features"

    private const val ATTRIBUTE_USAGE_METADATA = "better-dynamic-features-metadata"
    private const val ATTRIBUTE_USAGE_IMPLEMENTATIONS = "better-dynamic-features-implementations"
    private const val CONFIGURATION_BDF = "betterDynamicFeatures"
    private const val CONFIGURATION_BDF_IMPLEMENTATIONS = "betterDynamicFeaturesImplementations"

    @Suppress("FunctionName")
    private fun CONFIGURATION_BDF_COMPILE_CLASSPATH(buildType: String) = "${buildType}BetterDynamicFeaturesCompileClasspath"

    private val ARTIFACT_TYPE = Attribute.of("artifactType", String::class.java)
    private const val ARTIFACT_TYPE_FEATURE_DEPENDENCY_GRAPH = "feature-dependency-graph"

    private const val ARTIFACT_TYPE_BASE_DEPENDENCY_GRAPH = "base-dependency-graph"
    private const val ARTIFACT_TYPE_FEATURE_IMPLEMENTATION_REPORT = "feature-implementation-report"

    private const val VARIANT_DEPENDENCY_GRAPHS = "dependency-graphs"

    private val WRITE_DEPENDENCY_GRAPH_REGEX =
      Regex("write(.+)DependencyGraph", RegexOption.IGNORE_CASE)

    @Suppress("FunctionName")
    private fun VARIANT_FEATURE_IMPLEMENTATION(variant: String): String =
      "$variant-implementations"
  }
}

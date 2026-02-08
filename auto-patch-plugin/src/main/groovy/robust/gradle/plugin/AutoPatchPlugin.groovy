package robust.gradle.plugin

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import com.android.build.gradle.AppPlugin
import com.meituan.robust.Constants
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.logging.Logger

/**
 * Auto Patch Plugin for AGP 8.x
 * Uses the new Artifact API instead of deprecated Transform API
 */
class AutoPatchPlugin implements Plugin<Project> {
    Project project
    static Logger logger

    @Override
    void apply(Project project) {
        this.project = project
        logger = project.logger

        // Only apply to Android application projects
        project.plugins.withType(AppPlugin) {
            // Register the transformation using AGP 8.x API
            def androidComponents = project.extensions.getByType(AndroidComponentsExtension)
            androidComponents.onVariants(androidComponents.selector().all()) { variant ->
                def taskName = "autoPatchTransform${variant.name.capitalize()}"

                def taskProvider = project.tasks.register(taskName, AutoPatchTransformTask) { task ->
                    task.projectDir = project.projectDir
                    task.buildDir = project.buildDir
                    task.bootClasspath = project.android.bootClasspath
                }

                variant.artifacts
                    .forScope(ScopedArtifacts.Scope.ALL)
                    .use(taskProvider)
                    .toTransform(
                        ScopedArtifact.CLASSES.INSTANCE,
                        { task -> task.getAllJars() },
                        { task -> task.getAllDirectories() },
                        { task -> task.getOutput() }
                    )
            }

            logger.quiet "Register auto-patch-plugin successful (AGP 8.x compatible) !!!"
        }
    }
}

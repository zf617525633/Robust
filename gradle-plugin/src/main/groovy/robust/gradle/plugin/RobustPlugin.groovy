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
 * Robust Plugin for AGP 8.x
 * Uses the new Artifact API instead of deprecated Transform API
 */
class RobustPlugin implements Plugin<Project> {
    Project project
    static Logger logger
    private static List<String> hotfixPackageList = new ArrayList<>()
    private static List<String> hotfixMethodList = new ArrayList<>()
    private static List<String> exceptPackageList = new ArrayList<>()
    private static List<String> exceptMethodList = new ArrayList<>()
    private static boolean isHotfixMethodLevel = false
    private static boolean isExceptMethodLevel = false
    private static boolean isForceInsert = false
    private static boolean useASM = true
    private static boolean isForceInsertLambda = false

    def robust

    @Override
    void apply(Project project) {
        this.project = project
        logger = project.logger

        // Only apply to Android application projects
        project.plugins.withType(AppPlugin) {
            robust = new XmlSlurper().parse(new File("${project.projectDir}/${Constants.ROBUST_XML}"))
            initConfig()

            def shouldSkip = false
            if (!isForceInsert) {
                def taskNames = project.gradle.startParameter.taskNames
                def isDebugTask = false
                for (int index = 0; index < taskNames.size(); ++index) {
                    def taskName = taskNames[index]
                    logger.debug "input start parameter task is ${taskName}"
                    if (taskName.endsWith("Debug") && taskName.contains("Debug")) {
                        isDebugTask = true
                        break
                    }
                }
                if (isDebugTask) {
                    shouldSkip = true
                }
                if (null != robust.switch.turnOnRobust && !"true".equals(String.valueOf(robust.switch.turnOnRobust))) {
                    shouldSkip = true
                }
            }

            if (shouldSkip) {
                logger.quiet "Robust plugin skipped for debug build"
                return
            }

            // Register the transformation using AGP 8.x API
            def androidComponents = project.extensions.getByType(AndroidComponentsExtension)
            androidComponents.onVariants(androidComponents.selector().all()) { variant ->
                // Only apply to release variants
                if (variant.name.toLowerCase().contains("debug") && !isForceInsert) {
                    return
                }

                def taskName = "robustTransform${variant.name.capitalize()}"

                def taskProvider = project.tasks.register(taskName, RobustTransformTask) { task ->
                    task.hotfixPackageList = hotfixPackageList
                    task.hotfixMethodList = hotfixMethodList
                    task.exceptPackageList = exceptPackageList
                    task.exceptMethodList = exceptMethodList
                    task.isHotfixMethodLevel = isHotfixMethodLevel
                    task.isExceptMethodLevel = isExceptMethodLevel
                    task.useASM = useASM
                    task.isForceInsertLambda = isForceInsertLambda
                    task.buildDir = project.layout.buildDirectory.asFile.get()
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

            project.afterEvaluate(new RobustApkHashAction())
            logger.quiet "Register robust plugin successful (AGP 8.x compatible) !!!"
        }
    }

    def initConfig() {
        hotfixPackageList = new ArrayList<>()
        hotfixMethodList = new ArrayList<>()
        exceptPackageList = new ArrayList<>()
        exceptMethodList = new ArrayList<>()
        isHotfixMethodLevel = false
        isExceptMethodLevel = false

        for (name in robust.packname.name) {
            hotfixPackageList.add(name.text())
        }
        for (name in robust.exceptPackname.name) {
            exceptPackageList.add(name.text())
        }
        for (name in robust.hotfixMethod.name) {
            hotfixMethodList.add(name.text())
        }
        for (name in robust.exceptMethod.name) {
            exceptMethodList.add(name.text())
        }

        if (null != robust.switch.filterMethod && "true".equals(String.valueOf(robust.switch.turnOnHotfixMethod.text()))) {
            isHotfixMethodLevel = true
        }

        if (null != robust.switch.useAsm && "false".equals(String.valueOf(robust.switch.useAsm.text()))) {
            useASM = false
        } else {
            useASM = true
        }

        if (null != robust.switch.filterMethod && "true".equals(String.valueOf(robust.switch.turnOnExceptMethod.text()))) {
            isExceptMethodLevel = true
        }

        if (robust.switch.forceInsert != null && "true".equals(String.valueOf(robust.switch.forceInsert.text())))
            isForceInsert = true
        else
            isForceInsert = false

        if (robust.switch.forceInsertLambda != null && "true".equals(String.valueOf(robust.switch.forceInsertLambda.text())))
            isForceInsertLambda = true
        else
            isForceInsertLambda = false
    }
}

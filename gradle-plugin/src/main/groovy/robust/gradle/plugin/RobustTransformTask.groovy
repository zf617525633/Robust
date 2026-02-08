package robust.gradle.plugin

import com.meituan.robust.Constants
import javassist.ClassPool
import javassist.CtClass
import org.gradle.api.DefaultTask
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import robust.gradle.plugin.asm.AsmInsertImpl
import robust.gradle.plugin.javaassist.JavaAssistInsertImpl

import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.regex.Matcher
import java.util.zip.GZIPOutputStream

/**
 * Robust Transform Task for AGP 8.x
 */
abstract class RobustTransformTask extends DefaultTask {

    private static final String DOT_CLASS = ".class"

    @InputFiles
    abstract ListProperty<RegularFile> getAllJars()

    @InputFiles
    abstract ListProperty<Directory> getAllDirectories()

    @OutputFile
    abstract RegularFileProperty getOutput()

    @Input
    List<String> hotfixPackageList = new ArrayList<>()
    @Input
    List<String> hotfixMethodList = new ArrayList<>()
    @Input
    List<String> exceptPackageList = new ArrayList<>()
    @Input
    List<String> exceptMethodList = new ArrayList<>()
    @Input
    boolean isHotfixMethodLevel = false
    @Input
    boolean isExceptMethodLevel = false
    @Input
    boolean useASM = true
    @Input
    boolean isForceInsertLambda = false
    @Internal
    File buildDir
    @InputFiles
    List<File> bootClasspath = new ArrayList<>()

    @TaskAction
    void taskAction() {
        logger.quiet '================robust start================'
        def startTime = System.currentTimeMillis()

        File jarFile = output.get().asFile
        if (!jarFile.parentFile.exists()) {
            jarFile.parentFile.mkdirs()
        }
        if (jarFile.exists()) {
            jarFile.delete()
        }

        ClassPool classPool = new ClassPool()
        bootClasspath.each {
            classPool.appendClassPath((String) it.absolutePath)
        }

        def box = toCtClasses(classPool)
        def cost = (System.currentTimeMillis() - startTime) / 1000

        InsertcodeStrategy insertcodeStrategy
        if (useASM) {
            insertcodeStrategy = new AsmInsertImpl(hotfixPackageList, hotfixMethodList, exceptPackageList, exceptMethodList, isHotfixMethodLevel, isExceptMethodLevel, isForceInsertLambda)
        } else {
            insertcodeStrategy = new JavaAssistInsertImpl(hotfixPackageList, hotfixMethodList, exceptPackageList, exceptMethodList, isHotfixMethodLevel, isExceptMethodLevel, isForceInsertLambda)
        }
        insertcodeStrategy.insertCode(box, jarFile)
        writeMap2File(insertcodeStrategy.methodMap, Constants.METHOD_MAP_OUT_PATH)

        logger.quiet "===robust print id start==="
        for (String method : insertcodeStrategy.methodMap.keySet()) {
            int id = insertcodeStrategy.methodMap.get(method)
            System.out.println("key is   " + method + "  value is    " + id)
        }
        logger.quiet "===robust print id end==="

        cost = (System.currentTimeMillis() - startTime) / 1000
        logger.quiet "robust cost $cost second"
        logger.quiet '================robust   end================'
    }

    List<CtClass> toCtClasses(ClassPool classPool) {
        List<String> classNames = new ArrayList<>()
        List<CtClass> allClass = new ArrayList<>()
        def startTime = System.currentTimeMillis()

        // Process all directories
        allDirectories.get().each { directory ->
            def dirPath = directory.asFile.absolutePath
            classPool.insertClassPath(dirPath)
            org.apache.commons.io.FileUtils.listFiles(directory.asFile, null, true).each { file ->
                if (file.absolutePath.endsWith(DOT_CLASS)) {
                    def className = file.absolutePath.substring(dirPath.length() + 1, file.absolutePath.length() - DOT_CLASS.length()).replaceAll(Matcher.quoteReplacement(File.separator), '.')
                    if (className.contains("META-INF") || className.endsWith("module-info")) {
                        return
                    }
                    if (classNames.contains(className)) {
                        println "Warning: You have duplicate classes with the same name : " + className + " skip it "
                        return
                    }
                    classNames.add(className)
                }
            }
        }

        // Process all jars
        allJars.get().each { jarInput ->
            classPool.insertClassPath(jarInput.asFile.absolutePath)
            def jarFile = new JarFile(jarInput.asFile)
            Enumeration<JarEntry> classes = jarFile.entries()
            while (classes.hasMoreElements()) {
                JarEntry libClass = classes.nextElement()
                String className = libClass.getName()
                if (className.endsWith(DOT_CLASS)) {
                    className = className.substring(0, className.length() - DOT_CLASS.length()).replaceAll('/', '.')
                    if (className.contains("META-INF") || className.endsWith("module-info")) {
                        continue
                    }
                    if (classNames.contains(className)) {
                        println "Warning: You have duplicate classes with the same name : " + className + " skip it "
                        continue
                    }
                    classNames.add(className)
                }
            }
            jarFile.close()
        }

        def cost = (System.currentTimeMillis() - startTime) / 1000
        println "read all class file cost $cost second"

        classNames.each {
            try {
                allClass.add(classPool.get(it))
            } catch (javassist.NotFoundException e) {
                println "class not found exception class name:  $it "
            }
        }

        Collections.sort(allClass, new Comparator<CtClass>() {
            @Override
            int compare(CtClass class1, CtClass class2) {
                return class1.getName() <=> class2.getName()
            }
        })
        return allClass
    }

    private void writeMap2File(Map map, String path) {
        File file = new File(buildDir.path + path)
        if (!file.exists() && (!file.parentFile.mkdirs() || !file.createNewFile())) {
            // ignore
        }
        FileOutputStream fileOut = new FileOutputStream(file)

        ByteArrayOutputStream byteOut = new ByteArrayOutputStream()
        ObjectOutputStream objOut = new ObjectOutputStream(byteOut)
        objOut.writeObject(map)

        GZIPOutputStream gzip = new GZIPOutputStream(fileOut)
        gzip.write(byteOut.toByteArray())
        objOut.close()
        gzip.flush()
        gzip.close()
        fileOut.flush()
        fileOut.close()
    }
}

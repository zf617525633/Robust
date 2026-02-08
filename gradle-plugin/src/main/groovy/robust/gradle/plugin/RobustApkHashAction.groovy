package robust.gradle.plugin

import com.meituan.robust.Constants
import org.apache.commons.io.IOUtils
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.file.FileCollection

import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Created by hedex on 17/2/14.
 */
class RobustApkHashAction implements Action<Project> {
    @Override
    void execute(Project project) {
        project.android.applicationVariants.each { variant ->
            def packageTask = project.tasks.findByName("package${variant.name.capitalize()}")

            if (packageTask == null) {
                return
            }

            packageTask.doFirst {
                List<File> partFiles = new ArrayList<>()

                // Use a more robust way to collect files from packageTask properties
                def collectFiles = { propertyName ->
                    try {
                        def property = packageTask.hasProperty(propertyName) ? packageTask."$propertyName" : null
                        if (property != null) {
                            project.files(property).each { partFiles.add(it) }
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                }

                collectFiles("resourceFiles")
                collectFiles("dexFolders")
                collectFiles("javaResourceFiles")
                collectFiles("jniFolders")
                collectFiles("assets")

                String robustHash = computeRobustHash(partFiles)
                
                // For AGP 8.x, we should try to put the hash file into the assets
                // The previous logic tried to find .ap_ files in inputs
                for (File file : packageTask.getInputs().getFiles().getAsFileTree()) {
                    if (file.getAbsolutePath().endsWith(".ap_")) {
                        try {
                            createHashFile2(file.getAbsolutePath(), "assets/" + Constants.ROBUST_APK_HASH_FILE_NAME, robustHash);
                        } catch (IOException e) {
                        }
                    }
                }

                // Also save to build directory
                String buildRobustDir = "${project.buildDir}" + File.separator + "$Constants.ROBUST_GENERATE_DIRECTORY" + File.separator
                createHashFile(buildRobustDir, Constants.ROBUST_APK_HASH_FILE_NAME, robustHash)
            }
        }
    }


    def String computeRobustHash(ArrayList<File> partFiles) {
        File sumFile = new File("temp_robust_sum.zip")
        RobustApkHashZipUtils.packZip(sumFile, partFiles)
        String apkHashValue = fileMd5(sumFile)
        if (sumFile.exists()) {
            sumFile.delete()
        }
        return apkHashValue
    }

    def String fileMd5(File file) {
        if (!file.isFile()) {
            return "";
        }
        MessageDigest digest;
        byte[] buffer = new byte[4096];
        int len;
        try {
            digest = MessageDigest.getInstance("MD5");
            FileInputStream inputStream = new FileInputStream(file);
            while ((len = inputStream.read(buffer, 0, 1024)) != -1) {
                digest.update(buffer, 0, len);
            }
            inputStream.close();
        } catch (Exception e) {
            return "";
        }

        BigInteger bigInt = new BigInteger(1, digest.digest());
        return bigInt.toString(16);
    }

    def static File createHashFile(String dir, String hashFileName, String hashValue) {
        File hashFile = new File(dir, hashFileName)
        if (hashFile.exists()) {
            hashFile.delete()
        }

        FileWriter fileWriter = new FileWriter(hashFile)
        fileWriter.write(hashValue)
        fileWriter.close()
        return hashFile
    }

    def static void createHashFile2(String filePath, String fileName, String content) throws IOException {
        ZipInputStream zis = new ZipInputStream(new FileInputStream(filePath));
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(filePath + ".temp"));
        ZipEntry entry = zis.getNextEntry();
        while (entry != null) {
            zos.putNextEntry(new ZipEntry(entry.getName()));
            byte[] bytes = IOUtils.toByteArray(zis);
            zos.write(bytes);
            entry = zis.getNextEntry();
        }
        ZipEntry e = new ZipEntry(fileName);
//        System.out.println("append: " + e.getName());
        zos.putNextEntry(e);
        zos.write(content.getBytes());
        zos.closeEntry();

        zos.flush();
        zos.close();
        zis.close();

        try {
            new File(filePath).delete();
            new File(filePath + ".temp").renameTo(new File(filePath));
        } catch (Exception ex) {
        }
    }


    static boolean isGradlePlugin300orAbove(Project project) {
        //gradlePlugin3.0 -> gradle 4.1+
        return compare(project.getGradle().gradleVersion, "4.1") >= 0
    }

    static boolean isGradlePlugin320orAbove(Project project) {
        //gradlePlugin3.2.0 -> gradle 4.6+
        //see https://developer.android.com/studio/releases/gradle-plugin
        return compare(project.getGradle().gradleVersion, "4.6") >= 0
    }

    /**
     *
     * @param lhsVersion gradle version code {@code lhsVersion}
     * @param rhsVersion second gradle version code {@code rhsVersion} to compare with {@code lhsVersion}
     * @return an integer < 0 if {@code lhsVersion} is less than {@code rhsVersion}, 0 if they are
     *         equal, and > 0 if {@code lhsVersion} is greater than {@code rhsVersion}.
     */
    private static int compare(String lhsVersion, String rhsVersion) {
        def lhsArray = lhsVersion.split("\\.")
        def rhsArray = rhsVersion.split("\\.")
        for (int index = 0; index < rhsArray.size(); index++) {
            if (index < lhsArray.size()) {
                if (lhsArray[index] != rhsArray[index]) {
                    return lhsArray[index].toInteger() > rhsArray[index].toInteger() ? 1 : -1
                }
            } else {
                return -1
            }
        }
        return 0
    }

    static String getGradlePluginVersion() {
        String version = null
        try {
            def clazz = Class.forName("com.android.builder.Version")
            def field = clazz.getDeclaredField("ANDROID_GRADLE_PLUGIN_VERSION")
            field.setAccessible(true)
            version = field.get(null)
        } catch (Exception ignore) {
        }
        if (version == null) {
            try {
                def clazz = Class.forName("com.android.builder.model.Version")
                def field = clazz.getDeclaredField("ANDROID_GRADLE_PLUGIN_VERSION")
                field.setAccessible(true)
                version = field.get(null)
            } catch (Exception ignore) {
            }
        }
        return version
    }
}
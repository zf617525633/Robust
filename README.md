# Robust (Gradle 8.x Optimized)
 
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](https://github.com/Meituan-Dianping/Robust/pulls)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://raw.githubusercontent.com/Meituan-Dianping/Robust/master/LICENSE)  

Robust is an Android HotFix solution with high compatibility and high stability. Robust can fix bugs immediately without publishing apk.

This version has been optimized for **Gradle 8.x** and **Android Gradle Plugin (AGP) 8.x**.
 
 [中文说明](README-zh.md)
 
 More help on [Wiki](https://github.com/Meituan-Dianping/Robust/wiki)
 
# Environment

 * Mac, Linux, and Windows
 * **Gradle 8.0+** (Tested on 8.4)
 * **Android Gradle Plugin 8.0+** (Tested on 8.1.0)
 * **Java 17**
 
# Usage

1. Add the plugins to your module's `build.gradle` (usually the `app` module).

	```groovy
	plugins {
	    id 'com.android.application'
	    id 'robust'
	    // Uncomment the following line only when you want to build a patch
	    // id 'auto-patch-plugin'
	}

	dependencies {
	    implementation project(':patch')
	    implementation project(':autopatchbase')
	}
	```

2. Configuration items are in **app/robust.xml**, such as classes where Robust will insert code hooks. This may differ from project to project. Please copy this file to your project.

# Key Features (AGP 8.x Adaptations)

*   **Artifact API Migration**: Migrated from the deprecated `Transform` API to the new `ScopedArtifacts` API for better performance and compatibility with modern AGP.
*   **Java 17 & Java 7 Compatibility**: Automatically handles bytecode versioning, ensuring patch classes are compatible with `dx` even when building with Java 17.
*   **R8/ProGuard Support**: Improved mapping parser to support newer R8 formats, including JSON metadata and comment lines.
*   **Support 2.3 to 14+ Android OS**: High compatibility across versions.
*   **Method-level HotFix**: Immediate effect without reboot.

# AutoPatch
 
AutoPatch will generate patches for Robust automatically. Follow the steps below to generate patches.

# Steps

1. In `app/build.gradle`, put **'auto-patch-plugin'** after **'com.android.application'**.
2. Put **mapping.txt** and **methodsMap.robust** (generated during the APK build) into the **app/robust/** directory. Create the directory if it doesn't exist.
3. Mark modified methods with `@Modify`:

	```java
	@Modify
	protected void onCreate(Bundle savedInstanceState) {
	    super.onCreate(savedInstanceState);
	}
	```
	Use `@Add` when you need to add methods or classes.
	
4. Run the same gradle command used to build the APK (e.g., `./gradlew assembleRelease`).
5. You will find the patch at **app/build/outputs/robust/patch.jar**.

# Demo Usage
1. Build the release APK:

	```bash
	./gradlew clean assembleRelease --stacktrace --no-daemon
	```
2. Save **mapping.txt** (from `app/build/outputs/mapping/release/`) and **methodsMap.robust** (from `app/build/outputs/robust/`).
3. Copy these files to `app/robust/`.
4. Modify `MainActivity.java`, add `@Modify` to a method.
5. Enable `auto-patch-plugin` in `app/build.gradle`.
6. Run the build again:

	```bash
	./gradlew assembleRelease --stacktrace --no-daemon
	```
7. Generating patches usually ends with an `auto patch end successfully` message.
8. Push the patch to your phone:

	```bash
	adb push app/build/outputs/robust/patch.jar /sdcard/robust/patch.jar
	```
9. Open the app and click the **Patch** button to apply.

# Attentions

1. Inner classes' private constructors should be changed to public.
2. AutoPatch cannot handle situations where a method returns `this` directly; wrap it if necessary.
3. Added classes should be static nested or non-inner classes, and members should be public.
4. Support for resources and `.so` files is currently limited.

## License

    Copyright 2017 Meituan-Dianping

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Robust is an Android HotFix solution by Meituan-Dianping that enables fixing bugs without publishing a new APK. It works by inserting bytecode hooks at compile time that can be redirected to patch code at runtime.

## Build Commands

```bash
# Build release APK (also generates methodsMap.robust for patching)
./gradlew clean assembleRelease --stacktrace --no-daemon

# Build debug APK
./gradlew clean assembleDebug --stacktrace --no-daemon

# Clean build
./gradlew clean
```

## Generating Patches

1. Build the original APK and save `mapping.txt` and `build/outputs/robust/methodsMap.robust`
2. Place these files in `app/robust/` directory
3. Uncomment `apply plugin: 'auto-patch-plugin'` in `app/build.gradle`
4. Mark modified methods with `@Modify` annotation or call `RobustModify.modify()` inside them
5. Mark new methods/classes with `@Add` annotation
6. Run the same build command - patch will be generated at `app/build/outputs/robust/patch.jar`

## Architecture

### Module Structure

- **patch**: Runtime library included in the app. Contains `PatchExecutor` (loads patches via DexClassLoader), `PatchProxy` (intercepts method calls), and `ChangeQuickRedirect` interface
- **autopatchbase**: Shared code between plugins. Contains `@Modify` and `@Add` annotations, `RobustModify` marker class
- **gradle-plugin**: Build-time plugin (`robust`) that inserts `ChangeQuickRedirect` field and `PatchProxy` calls into every method using ASM or Javassist
- **auto-patch-plugin**: Build-time plugin (`auto-patch-plugin`) that generates patch classes by reading `@Modify`/`@Add` annotations and creating corresponding patch implementations
- **app**: Demo application

### How Hotfix Works

1. **Compile time**: `RobustTransform` inserts a static `ChangeQuickRedirect` field into each class and adds proxy checks at the start of each method
2. **Runtime**: When a patch is loaded, `PatchExecutor` uses reflection to set the `ChangeQuickRedirect` field to the patch implementation
3. **Method execution**: `PatchProxy.proxy()` checks if `ChangeQuickRedirect` is set; if so, it redirects execution to the patch code

### Key Configuration

`app/robust.xml` controls:
- `turnOnRobust`: Enable/disable Robust
- `forceInsert`: Force code insertion in debug builds
- `useAsm`: Use ASM (default) vs Javassist for bytecode manipulation
- `proguard`: Whether project uses ProGuard
- `hotfixPackage`: Package names to instrument (e.g., `com.meituan`)
- `exceptPackage`: Package names to exclude (e.g., `com.meituan.robust`)
- `patchPackname`: Package name for generated patches

## Limitations

- Cannot add new fields (can add classes)
- Methods returning `this` need wrapping
- Inner class private constructors must be changed to public
- Added classes must be static nested or non-inner, with all members public

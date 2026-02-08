# Robust 热修复框架适配 Gradle 8.x & AGP 8.x 纪要文档

## 1. 适配背景
随着 Android 开发环境升级至 Gradle 8.x 和 AGP 8.x，原有的 `Transform` API 已被彻底移除。本项目旨在将 Robust 插件迁移至新的 `Artifact` API 体系，并解决在 Java 17 编译环境下产生的一系列兼容性问题。

## 2. 运行环境
*   **Gradle**: 8.4
*   **AGP (Android Gradle Plugin)**: 8.1.0
*   **Java 版本**: 17
*   **编译目标 (Compile SDK)**: 34

## 3. 核心修改详情

### 3.1 插件架构迁移
*   **API 替换**：将 `RobustPlugin` 和 `AutoPatchPlugin` 从已废弃的 `Transform` 模式迁移至 `AndroidComponentsExtension`。
*   **Artifact 接入**：利用 `variant.artifacts.forScope(ScopedArtifacts.Scope.ALL).use(taskProvider).toTransform(...)` 方法接入类转换流程。
*   **混合语言兼容**：在 Groovy 脚本中调用 Kotlin 单例时，显式使用 `ScopedArtifact.CLASSES.INSTANCE`，解决了 Groovy 闭包与 Kotlin `Function1` 之间的调用歧义。

### 3.2 Task 属性与验证适配 (Gradle 8.x 规范)
*   **严格校验修复**：为 `RobustTransformTask` 和 `AutoPatchTransformTask` 的所有成员变量补充了 Gradle 必要的任务注解：
    *   `@Input`: 用于配置参数（如包名列表、布尔开关）。
    *   `@InputFiles`: 用于输入路径（如 `bootClasspath`）。
    *   `@OutputFile`: 用于转换后的 JAR 输出。
    *   `@Internal`: 用于不参与增量检查的内部变量。
*   **重复类冲突解决**：在类收集逻辑 `toCtClasses` 中增加了对 `META-INF` 目录及 `module-info.class` 的过滤，避免了在合并全量库文件时因重复资源导致的构建失败。

### 3.3 字节码与工具链兼容性
*   **类版本降级**：修复了在 Java 17 环境下生成的补丁类（版本 61.0）无法被 `dx` 工具识别的问题。在 `PatchesControlFactory` 等工厂类中强制设置 `setMajorVersion(ClassFile.JAVA_7)`，确保补丁字节码维持在 Java 7 (51.0) 水平。
*   **R8 Mapping 解析增强**：更新了 `ReadMapping.java` 的解析逻辑，增加了对以 `#` 开头的注释行的跳过逻辑处理，支持了 R8 生成的包含 JSON 元数据的新版 mapping 格式。
*   **Java 17 语法兼容性修复**：
    *   修复了 `PatchTemplate.java` 中 `new Boolean()` 构造函数在 Java 9+ 中过时并被标记为删除的问题，统一替换为 `Boolean.valueOf()`。
    *   在 `EnhancedRobustUtils.java` 中移除了已过时的 `isAccessible()` 检查，直接调用 `setAccessible(true)`，以消除 Java 编译警告。
*   **宽松匹配逻辑**：调整了混淆映射匹配机制，当开启 ProGuard 但在 mapping 文件中未找到对应类时（常见于被 Keep 的类），默认使用原始类名，增强了补丁生成的稳定性。

### 3.4 任务执行逻辑优化
*   **Hash 动作适配**：重写了 `RobustApkHashAction`，使用 `project.files(property)` 包装方式兼容了 AGP 8.x 中将 `resourceFiles` 等属性重构为 `DirectoryProperty` 的变更。
*   **冗余清理**：彻底删除了项目中的 `RobustTransform.groovy` 和 `AutoPatchTransform.groovy` 等旧版 API 实现文件，简化了插件结构。

## 4. 验证结果
1.  **APK 构建流**：执行 `./gradlew :app:assembleRelease` 可顺利通过字节码插桩阶段，生成带有跳转钩子的应用包。
2.  **补丁生成流**：
    *   通过 `@Modify` 注解标记修改点。
    *   成功读取 `app/robust/` 下的 `mapping.txt` 和 `methodsMap.robust`。
    *   顺利调用 `dx.jar` 完成补丁转 dex 操作。
    *   最终在 `app/build/outputs/robust/` 目录下生成可用的 `patch.jar`。

## 5. 后续注意事项
*   **插件引入**：当前通过 `settings.gradle` 中的 `includeBuild` 关联插件项目，如需发布到私有仓库，请更新 `gradle_mvn_push.gradle` 中的版本号。
*   **R8 优化**：在某些极端优化场景下，若 R8 移除了未使用的类导致 mapping 缺失，插件已具备基础的回退保护，但仍建议在 `proguard-rules.pro` 中对业务核心类进行必要的 `-keep` 配置。

---
**纪要人**: Gemini CLI 工程师  
**日期**: 2026年2月8日

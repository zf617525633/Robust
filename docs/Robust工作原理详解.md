# Robust 热修复框架工作原理详解

Robust 是美团点评开源的 Android 热修复框架，采用**方法级别的代码重定向**技术，实现无需重启应用即可修复线上 Bug。

---

## 目录

1. [整体架构](#1-整体架构)
2. [模块说明](#2-模块说明)
3. [编译期插桩原理](#3-编译期插桩原理)
4. [运行时补丁加载原理](#4-运行时补丁加载原理)
5. [补丁生成原理](#5-补丁生成原理)
6. [核心类与接口](#6-核心类与接口)
7. [使用流程](#7-使用流程)
8. [限制与注意事项](#8-限制与注意事项)

---

## 1. 整体架构

Robust 的工作流程分为三个阶段：

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Robust 热修复流程                                │
├─────────────────────┬─────────────────────┬─────────────────────────────────┤
│    编译期 (Build)    │   补丁生成期 (Patch) │        运行期 (Runtime)          │
├─────────────────────┼─────────────────────┼─────────────────────────────────┤
│                     │                     │                                 │
│  ┌───────────────┐  │  ┌───────────────┐  │  ┌───────────────┐              │
│  │ 原始 Class    │  │  │ 修改后的代码   │  │  │ 下载 patch.jar │              │
│  └───────┬───────┘  │  └───────┬───────┘  │  └───────┬───────┘              │
│          │          │          │          │          │                      │
│          ▼          │          ▼          │          ▼                      │
│  ┌───────────────┐  │  ┌───────────────┐  │  ┌───────────────┐              │
│  │ ASM 字节码插桩 │  │  │ 扫描 @Modify  │  │  │ DexClassLoader │              │
│  │ 插入代理代码   │  │  │ @Add 注解     │  │  │ 加载补丁       │              │
│  └───────┬───────┘  │  └───────┬───────┘  │  └───────┬───────┘              │
│          │          │          │          │          │                      │
│          ▼          │          ▼          │          ▼                      │
│  ┌───────────────┐  │  ┌───────────────┐  │  ┌───────────────┐              │
│  │ 生成 APK      │  │  │ 生成补丁类    │  │  │ 反射设置       │              │
│  │ methodsMap    │  │  │ Control 类    │  │  │ changeQuick-   │              │
│  │ .robust       │  │  │ PatchesInfo   │  │  │ Redirect 字段  │              │
│  └───────────────┘  │  └───────┬───────┘  │  └───────┬───────┘              │
│                     │          │          │          │                      │
│                     │          ▼          │          ▼                      │
│                     │  ┌───────────────┐  │  ┌───────────────┐              │
│                     │  │ 打包 patch.jar│  │  │ 方法调用时     │              │
│                     │  └───────────────┘  │  │ 自动重定向到   │              │
│                     │                     │  │ 补丁代码       │              │
│                     │                     │  └───────────────┘              │
└─────────────────────┴─────────────────────┴─────────────────────────────────┘
```

---

## 2. 模块说明

### 2.1 项目结构

```
Robust/
├── patch/                 # 运行时库 (包含在 APK 中)
├── autopatchbase/         # 共享注解和接口
├── gradle-plugin/         # 编译期字节码插桩插件
├── auto-patch-plugin/     # 补丁生成插件
└── app/                   # 示例应用
```

### 2.2 各模块职责

| 模块 | 职责 | 关键类 |
|------|------|--------|
| **patch** | 运行时加载和应用补丁 | `PatchExecutor`, `PatchProxy`, `PatchManipulate` |
| **autopatchbase** | 定义注解和核心接口 | `@Modify`, `@Add`, `ChangeQuickRedirect` |
| **gradle-plugin** | 编译期插入代理代码 | `RobustPlugin`, `AsmInsertImpl`, `RobustAsmUtils` |
| **auto-patch-plugin** | 生成补丁包 | `AutoPatchPlugin`, `PatchesFactory`, `ReadAnnotation` |

---

## 3. 编译期插桩原理

### 3.1 插桩目标

在每个需要热修复的类中：
1. **添加静态字段**: `public static ChangeQuickRedirect changeQuickRedirect`
2. **在每个方法开头插入代理检查代码**

### 3.2 插桩前后对比

**插桩前的原始代码：**
```java
public class MainActivity {
    public String getText() {
        return "Hello World";
    }
}
```

**插桩后的代码：**
```java
public class MainActivity {
    // 插入的静态字段
    public static ChangeQuickRedirect changeQuickRedirect;

    public String getText() {
        // 插入的代理检查代码
        PatchProxyResult result = PatchProxy.proxy(
            new Object[]{},           // 方法参数数组
            this,                      // 当前对象 (静态方法为 null)
            changeQuickRedirect,       // 重定向接口
            false,                     // 是否静态方法
            1,                         // 方法唯一 ID
            new Class[]{},            // 参数类型数组
            String.class              // 返回值类型
        );
        if (result.isSupported) {
            return (String) result.result;  // 返回补丁执行结果
        }

        // 原始代码
        return "Hello World";
    }
}
```

### 3.3 ASM 插桩实现

核心实现位于 `AsmInsertImpl.java` 和 `RobustAsmUtils.java`：

```
AsmInsertImpl.java:54-56
├── 判断类是否需要插桩 (isNeedInsertClass)
├── 调用 transformCode() 进行字节码转换
└── 输出到 JAR 文件

AsmInsertImpl.java:66-84 (InsertMethodBodyAdapter)
├── 添加 changeQuickRedirect 静态字段
└── 遍历每个方法，调用 MethodBodyInsertor

AsmInsertImpl.java:172-200 (MethodBodyInsertor)
└── visitCode() 中调用 RobustAsmUtils.createInsertCode()

RobustAsmUtils.java:27-71 (createInsertCode)
├── 准备方法参数 (prepareMethodParameters)
├── 调用 PatchProxy.proxy()
├── 检查 isSupported 字段
└── 根据返回类型生成 return 指令
```

### 3.4 方法过滤规则

以下方法**不会**被插桩：
- 构造函数 `<init>` 和类初始化 `<clinit>`
- 抽象方法 (`abstract`)
- 本地方法 (`native`)
- 接口方法 (`interface`)
- 被标记为 `@Deprecated` 的方法
- `exceptPackage` 配置中排除的包

### 3.5 methodsMap.robust 文件

插桩过程会生成 `methodsMap.robust` 文件，记录每个方法的唯一 ID：

```
com.meituan.sample.MainActivity.getText():1
com.meituan.sample.MainActivity.onClick(android.view.View):2
com.meituan.sample.SecondActivity.onCreate(android.os.Bundle):3
...
```

---

## 4. 运行时补丁加载原理

### 4.1 加载流程

```
PatchExecutor.run()
    │
    ├── 1. fetchPatchList()
    │       └── 调用 PatchManipulate.fetchPatchList() 获取补丁列表
    │
    ├── 2. applyPatchList(patches)
    │       └── 遍历每个补丁，调用 patch()
    │
    └── 3. patch(context, patch)
            │
            ├── 3.1 verifyPatch() - 验证补丁 MD5
            │
            ├── 3.2 DexClassLoader 加载补丁 DEX
            │       new DexClassLoader(patch.getTempPath(), ...)
            │
            ├── 3.3 加载 PatchesInfoImpl 类
            │       classLoader.loadClass("com.meituan.robust.patch.PatchesInfoImpl")
            │
            ├── 3.4 获取需要修复的类列表
            │       patchesInfo.getPatchedClassesInfo()
            │
            └── 3.5 反射设置 changeQuickRedirect 字段
                    for (PatchedClassInfo info : patchedClasses) {
                        Class sourceClass = classLoader.loadClass(info.patchedClassName);
                        Field field = findChangeQuickRedirectField(sourceClass);
                        Class patchClass = classLoader.loadClass(info.patchClassName);
                        Object patchObject = patchClass.newInstance();
                        field.set(null, patchObject);  // 设置静态字段
                    }
```

### 4.2 PatchProxy.proxy() 方法

这是运行时的核心分发方法：

```java
// PatchProxy.java:40-47
public static PatchProxyResult proxy(
    Object[] paramsArray,              // 方法参数
    Object current,                    // this 对象
    ChangeQuickRedirect changeQuickRedirect,  // 补丁实现
    boolean isStatic,                  // 是否静态方法
    int methodNumber,                  // 方法 ID
    Class[] paramsClassTypes,          // 参数类型
    Class returnType                   // 返回类型
) {
    PatchProxyResult result = new PatchProxyResult();
    if (isSupport(...)) {
        result.isSupported = true;
        result.result = accessDispatch(...);  // 执行补丁代码
    }
    return result;
}
```

### 4.3 ChangeQuickRedirect 接口

```java
// ChangeQuickRedirect.java
public interface ChangeQuickRedirect {
    // 判断是否支持修复该方法
    boolean isSupport(String methodName, Object[] paramArrayOfObject);

    // 执行补丁代码
    Object accessDispatch(String methodName, Object[] paramArrayOfObject);
}
```

---

## 5. 补丁生成原理

### 5.1 生成流程

```
AutoPatchTransformTask
    │
    ├── 1. 读取配置
    │       ├── robust.xml (配置项)
    │       ├── methodsMap.robust (方法ID映射)
    │       └── mapping.txt (ProGuard混淆映射)
    │
    ├── 2. 扫描注解 (ReadAnnotation.groovy)
    │       ├── scanClassForAddClassAnnotation() - 扫描 @Add 类
    │       ├── scanClassForModifyMethod() - 扫描 @Modify 方法
    │       └── scanClassForAddMethodAnnotation() - 扫描 @Add 方法
    │
    ├── 3. 生成补丁类 (PatchesFactory.groovy)
    │       ├── 克隆被修改的类
    │       ├── 移除不需要修复的方法
    │       ├── 添加构造函数 (接收原始对象实例)
    │       ├── 处理字段访问 (转为反射)
    │       ├── 处理方法调用 (转为反射)
    │       └── 处理 super 方法调用
    │
    ├── 4. 生成控制类 (PatchesControlFactory)
    │       ├── 实现 ChangeQuickRedirect 接口
    │       ├── isSupport() - 根据方法ID判断
    │       └── accessDispatch() - 分发到对应补丁方法
    │
    ├── 5. 生成 PatchesInfoImpl
    │       └── 返回所有被修复的类信息
    │
    └── 6. 打包成 patch.jar
            └── 包含 DEX 文件
```

### 5.2 @Modify 注解

```java
// Modify.java
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.TYPE, ElementType.CONSTRUCTOR})
@Retention(RetentionPolicy.CLASS)
public @interface Modify {
    String value() default "";  // 可指定要修复的原始方法签名
}
```

**使用方式：**
```java
public class MainActivity {
    @Modify
    public String getText() {
        return "Fixed Text";  // 修复后的代码
    }
}
```

### 5.3 @Add 注解

用于标记新增的类或方法：

```java
// Add.java
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.TYPE, ElementType.CONSTRUCTOR})
@Retention(RetentionPolicy.CLASS)
public @interface Add {
    String value() default "";
}
```

**使用方式：**
```java
@Add
public class NewHelper {
    @Add
    public static void newMethod() {
        // 新增的方法
    }
}
```

### 5.4 生成的补丁类结构

对于被修复的 `MainActivity`，会生成：

```java
// MainActivityPatch.java (补丁类)
public class MainActivityPatch {
    MainActivity originClass;  // 持有原始对象引用

    public MainActivityPatch(Object obj) {
        this.originClass = (MainActivity) obj;
    }

    public String getText() {
        return "Fixed Text";
    }
}

// MainActivityPatchControl.java (控制类，实现 ChangeQuickRedirect)
public class MainActivityPatchControl implements ChangeQuickRedirect {

    @Override
    public boolean isSupport(String methodName, Object[] params) {
        // 根据方法ID判断是否支持
        return methodName.contains(":1");  // 方法ID为1
    }

    @Override
    public Object accessDispatch(String methodName, Object[] params) {
        // 创建补丁实例并调用对应方法
        MainActivity originObj = (MainActivity) params[params.length - 1];
        MainActivityPatch patch = new MainActivityPatch(originObj);
        return patch.getText();
    }
}

// PatchesInfoImpl.java (补丁信息类)
public class PatchesInfoImpl implements PatchesInfo {
    @Override
    public List<PatchedClassInfo> getPatchedClassesInfo() {
        List<PatchedClassInfo> list = new ArrayList<>();
        list.add(new PatchedClassInfo(
            "com.meituan.sample.MainActivity",           // 原始类名
            "com.meituan.robust.patch.MainActivityPatchControl"  // 补丁控制类名
        ));
        return list;
    }
}
```

---

## 6. 核心类与接口

### 6.1 patch 模块

| 类名 | 职责 |
|------|------|
| `PatchExecutor` | 补丁加载执行器，继承 Thread，负责下载、验证、加载补丁 |
| `PatchProxy` | 代理分发器，判断是否执行补丁代码 |
| `PatchProxyResult` | 代理结果封装，包含 isSupported 和 result |
| `Patch` | 补丁数据模型，包含名称、URL、MD5 等信息 |
| `PatchManipulate` | 抽象类，需要 App 实现补丁获取和验证逻辑 |
| `PatchesInfo` | 接口，返回被修复的类列表 |
| `PatchedClassInfo` | 原始类名和补丁类名的映射 |
| `RobustCallBack` | 回调接口，通知补丁加载状态 |

### 6.2 autopatchbase 模块

| 类名 | 职责 |
|------|------|
| `ChangeQuickRedirect` | 核心接口，补丁控制类必须实现 |
| `@Modify` | 标记需要修复的方法或类 |
| `@Add` | 标记新增的方法或类 |
| `RobustModify` | 备用标记方式，在方法内调用 `RobustModify.modify()` |
| `Constants` | 常量定义 |

### 6.3 gradle-plugin 模块

| 类名 | 职责 |
|------|------|
| `RobustPlugin` | Gradle 插件入口，读取配置，注册 Transform |
| `RobustTransformTask` | Transform 任务，执行字节码插桩 |
| `AsmInsertImpl` | ASM 实现的插桩策略 |
| `JavaAssistInsertImpl` | Javassist 实现的插桩策略 (备选) |
| `RobustAsmUtils` | ASM 工具类，生成代理代码 |
| `InsertcodeStrategy` | 插桩策略抽象基类 |

### 6.4 auto-patch-plugin 模块

| 类名 | 职责 |
|------|------|
| `AutoPatchPlugin` | 补丁生成插件入口 |
| `AutoPatchTransformTask` | 补丁生成任务 |
| `ReadAnnotation` | 扫描 @Modify/@Add 注解 |
| `PatchesFactory` | 生成补丁类 |
| `PatchesControlFactory` | 生成控制类 (实现 ChangeQuickRedirect) |
| `PatchesInfoFactory` | 生成 PatchesInfoImpl |
| `ReadMapping` | 解析 ProGuard mapping.txt |
| `ReflectUtils` | 生成反射调用代码 |

---

## 7. 使用流程

### 7.1 集成 Robust

**1. 添加依赖 (project build.gradle):**
```groovy
buildscript {
    dependencies {
        classpath 'com.meituan.robust:gradle-plugin:0.4.99'
        classpath 'com.meituan.robust:auto-patch-plugin:0.4.99'
    }
}
```

**2. 应用插件 (app build.gradle):**
```groovy
apply plugin: 'robust'
// 生成补丁时取消注释
// apply plugin: 'auto-patch-plugin'

dependencies {
    implementation 'com.meituan.robust:patch:0.4.99'
}
```

**3. 配置 robust.xml:**
```xml
<resources>
    <switch>
        <turnOnRobust>true</turnOnRobust>
        <useAsm>true</useAsm>
        <proguard>true</proguard>
    </switch>

    <packname name="hotfixPackage">
        <name>com.yourapp</name>
    </packname>

    <exceptPackname name="exceptPackage">
        <name>com.meituan.robust</name>
    </exceptPackname>

    <patchPackname name="patchPackname">
        <name>com.meituan.robust.patch</name>
    </patchPackname>
</resources>
```

### 7.2 发布原始 APK

```bash
./gradlew clean assembleRelease
```

保存以下文件用于后续生成补丁：
- `app/build/outputs/robust/methodsMap.robust`
- `app/build/outputs/mapping/release/mapping.txt`

### 7.3 生成补丁

**1. 将保存的文件放入 `app/robust/` 目录**

**2. 修改代码并添加注解:**
```java
public class MainActivity {
    @Modify
    public String getBuggyText() {
        return "Fixed!";  // 修复后的代码
    }
}
```

**3. 启用补丁插件并构建:**
```groovy
// app/build.gradle
apply plugin: 'auto-patch-plugin'
```

```bash
./gradlew clean assembleRelease
```

**4. 获取补丁文件:**
- `app/build/outputs/robust/patch.jar`

### 7.4 加载补丁

```java
public class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        // 启动补丁加载
        new PatchExecutor(
            getApplicationContext(),
            new PatchManipulateImp(),  // 自定义实现
            new RobustCallBackImpl()   // 回调
        ).start();
    }
}

public class PatchManipulateImp extends PatchManipulate {
    @Override
    protected List<Patch> fetchPatchList(Context context) {
        // 从服务器或本地获取补丁列表
        Patch patch = new Patch();
        patch.setName("patch_1");
        patch.setLocalPath("/sdcard/patch.jar");
        patch.setPatchesInfoImplClassFullName(
            "com.meituan.robust.patch.PatchesInfoImpl"
        );
        return Collections.singletonList(patch);
    }

    @Override
    protected boolean verifyPatch(Context context, Patch patch) {
        // 验证补丁 MD5
        return true;
    }

    @Override
    protected boolean ensurePatchExist(Patch patch) {
        // 确保补丁文件存在
        return new File(patch.getLocalPath()).exists();
    }
}
```

---

## 8. 限制与注意事项

### 8.1 不支持的场景

| 限制 | 说明 | 解决方案 |
|------|------|----------|
| **不能新增字段** | 类的字段结构在编译期确定 | 使用 Map 存储新数据，或新增类 |
| **构造函数** | 构造函数不会被插桩 | 将逻辑移到普通方法中 |
| **返回 this** | 补丁类无法返回原始对象 | 封装方法，避免链式调用 |
| **内部类私有构造** | 反射访问受限 | 改为 public 或 package 可见性 |
| **泛型方法** | 注解可能无法正确识别 | 使用 `RobustModify.modify()` 标记 |

### 8.2 性能影响

- **包体积增加**: 约 3%-5%，因为每个方法都插入了代理代码
- **运行时开销**: 极小，仅在方法入口处增加一次 null 检查
- **首次加载**: 补丁加载使用 DexClassLoader，有一定耗时

### 8.3 最佳实践

1. **保存构建产物**: 每次发版都保存 `methodsMap.robust` 和 `mapping.txt`
2. **测试补丁**: 在测试环境充分验证补丁效果
3. **版本匹配**: 确保补丁与 APK 版本匹配 (通过 APK Hash 校验)
4. **异常处理**: 开启 `catchReflectException` 防止补丁崩溃
5. **灰度发布**: 先小范围验证，再全量推送

### 8.4 与其他热修复方案对比

| 特性 | Robust | Tinker | Sophix |
|------|--------|--------|--------|
| 即时生效 | ✅ | ❌ (需重启) | ✅ |
| 兼容性 | 极高 | 高 | 高 |
| 修复粒度 | 方法级 | 类级 | 方法级 |
| 资源修复 | ❌ | ✅ | ✅ |
| so 修复 | ❌ | ✅ | ✅ |
| 包体积影响 | 中 | 小 | 小 |

---

## 附录：关键代码路径

```
编译期插桩:
  gradle-plugin/src/main/groovy/robust/gradle/plugin/RobustPlugin.groovy
  gradle-plugin/src/main/groovy/robust/gradle/plugin/asm/AsmInsertImpl.java
  gradle-plugin/src/main/groovy/robust/gradle/plugin/asm/RobustAsmUtils.java

运行时加载:
  patch/src/main/java/com/meituan/robust/PatchExecutor.java
  patch/src/main/java/com/meituan/robust/PatchProxy.java
  autopatchbase/src/main/java/com/meituan/robust/ChangeQuickRedirect.java

补丁生成:
  auto-patch-plugin/src/main/groovy/robust/gradle/plugin/AutoPatchPlugin.groovy
  auto-patch-plugin/src/main/groovy/com/meituan/robust/autopatch/ReadAnnotation.groovy
  auto-patch-plugin/src/main/groovy/com/meituan/robust/autopatch/PatchesFactory.groovy
```

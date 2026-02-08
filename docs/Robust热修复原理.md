# Robust 热修复原理深度解析

Robust 是美团点评开源的一款高性能、高兼容性的 Android 热修复框架。其核心技术方案基于 **方法重定向 (Method Redirection)**。

---

## 1. 核心工作流程

Robust 的运行主要分为三个阶段：**编译期插桩**、**补丁生成期** 和 **运行期加载**。

### 1.1 编译期插桩 (Gradle Plugin)
在应用构建阶段，Robust 插件通过 ASM 或 Javassist 字节码工具，对业务代码进行以下处理：
*   **注入静态变量**：在每个类中插入一个 `public static ChangeQuickRedirect changeQuickRedirect` 变量。
*   **注入拦截逻辑**：在每个方法的入口处插入一段 `If-Else` 判断。如果 `changeQuickRedirect` 不为空，则通过该变量分发到补丁逻辑。

### 1.2 补丁生成期 (AutoPatch)
当需要修复 Bug 时，开发者在修改的方法上标注 `@Modify`。插件会：
*   **代码比对**：通过 `methodsMap.robust` 确定受影响的方法。
*   **类名还原**：利用混淆 `mapping.txt` 确保补丁类能正确引用混淆后的代码。
*   **生成补丁类**：自动创建一个实现了 `ChangeQuickRedirect` 接口的类，并将修复后的代码写入 `accessDispatch` 方法中。
*   **Dex化**：调用 `dx` 工具将补丁类打包成 `patch.jar`。

### 1.3 运行期加载 (Runtime)
1.  **加载 Dex**：客户端通过 `DexClassLoader` 加载下载好的补丁包。
2.  **反射赋值**：找到补丁包中的修复类，反射赋值给目标类中的 `changeQuickRedirect` 变量。
3.  **实时生效**：由于方法头部已有拦截逻辑，下一次调用该方法时，程序会自动跳转到补丁代码，实现即时修复。

---

## 2. 优点与局限性

### 优点
*   **高兼容性**：不涉及 ART/Dalvik 虚拟机底层的结构修改，几乎支持所有 Android 版本。
*   **即时生效**：无需重启应用即可生效（Instant Fix）。
*   **高稳定性**：由于是纯 Java 层面的逻辑重定向，成功率极高。

### 局限性
*   **包体积**：插桩会增加方法的指令数，对包体积有一定影响（约为 3%-5%）。
*   **方法限制**：无法直接新增成员变量（可以通过新增类或 Map 方式变通处理）。

---

## 3. 热修复流程图 (Draw.io XML Source)

你可以将以下 XML 粘贴到 [Draw.io](https://app.diagrams.net) 预览：

```xml
<mxfile host="app.diagrams.net">
  <diagram name="Robust热修复流程">
    <mxGraphModel dx="1038" dy="651" grid="1" gridSize="10" guides="1" tooltips="1" connect="1" arrows="1" fold="1" page="1" pageScale="1" pageWidth="827" pageHeight="1169">
      <root>
        <mxCell id="0" />
        <mxCell id="1" parent="0" />
        <mxCell id="2" value="编译期 (Gradle Plugin)" style="swimlane;whiteSpace=wrap" vertex="1" parent="1">
          <mxGeometry x="40" y="40" width="200" height="400" as="geometry" />
        </mxCell>
        <mxCell id="3" value="遍历Class文件" style="rounded=1;whiteSpace=wrap" vertex="1" parent="2">
          <mxGeometry x="40" y="50" width="120" height="40" as="geometry" />
        </mxCell>
        <mxCell id="4" value="插入静态变量&lt;br&gt;changeQuickRedirect" style="rounded=0;whiteSpace=wrap" vertex="1" parent="2">
          <mxGeometry x="40" y="130" width="120" height="50" as="geometry" />
        </mxCell>
        <mxCell id="5" value="在方法头部&lt;br&gt;插入If判断" style="rounded=0;whiteSpace=wrap" vertex="1" parent="2">
          <mxGeometry x="40" y="220" width="120" height="50" as="geometry" />
        </mxCell>
        <mxCell id="6" value="生成APK及&lt;br&gt;methodsMap" style="shape=parallelogram;whiteSpace=wrap" vertex="1" parent="2">
          <mxGeometry x="40" y="310" width="120" height="50" as="geometry" />
        </mxCell>
        <mxCell id="7" value="运行期 (Runtime)" style="swimlane;whiteSpace=wrap" vertex="1" parent="1">
          <mxGeometry x="520" y="40" width="200" height="400" as="geometry" />
        </mxCell>
        <mxCell id="8" value="下载并加载&lt;br&gt;Patch.jar" style="rounded=1;whiteSpace=wrap" vertex="1" parent="7">
          <mxGeometry x="40" y="50" width="120" height="40" as="geometry" />
        </mxCell>
        <mxCell id="9" value="反射给目标类&lt;br&gt;cqr变量赋值" style="rounded=0;whiteSpace=wrap" vertex="1" parent="7">
          <mxGeometry x="40" y="130" width="120" height="50" as="geometry" />
        </mxCell>
        <mxCell id="10" value="执行方法" style="rhombus;whiteSpace=wrap" vertex="1" parent="7">
          <mxGeometry x="50" y="210" width="100" height="80" as="geometry" />
        </mxCell>
        <mxCell id="11" value="运行原逻辑" style="rounded=0;whiteSpace=wrap" vertex="1" parent="7">
          <mxGeometry x="10" y="320" width="80" height="40" as="geometry" />
        </mxCell>
        <mxCell id="12" value="跳转补丁逻辑" style="rounded=0;whiteSpace=wrap" vertex="1" parent="7">
          <mxGeometry x="110" y="320" width="80" height="40" as="geometry" />
        </mxCell>
        <mxCell id="13" value="补丁生成期 (AutoPatch)" style="swimlane;whiteSpace=wrap" vertex="1" parent="1">
          <mxGeometry x="280" y="40" width="200" height="400" as="geometry" />
        </mxCell>
        <mxCell id="14" value="读取@Modify注解" style="rounded=1;whiteSpace=wrap" vertex="1" parent="13">
          <mxGeometry x="40" y="50" width="120" height="40" as="geometry" />
        </mxCell>
        <mxCell id="15" value="根据Mapping还原类名" style="rounded=0;whiteSpace=wrap" vertex="1" parent="13">
          <mxGeometry x="40" y="130" width="120" height="50" as="geometry" />
        </mxCell>
        <mxCell id="16" value="生成补丁类&lt;br&gt;实现CQR接口" style="rounded=0;whiteSpace=wrap" vertex="1" parent="13">
          <mxGeometry x="40" y="220" width="120" height="50" as="geometry" />
        </mxCell>
        <mxCell id="17" value="dx工具打包成Dex" style="rounded=0;whiteSpace=wrap" vertex="1" parent="13">
          <mxGeometry x="40" y="310" width="120" height="50" as="geometry" />
        </mxCell>
        <mxCell id="18" style="edgeStyle=orthogonalEdgeStyle" edge="1" parent="1" source="3" target="4"><mxGeometry relative="1" as="geometry"/></mxCell>
        <mxCell id="19" style="edgeStyle=orthogonalEdgeStyle" edge="1" parent="1" source="4" target="5"><mxGeometry relative="1" as="geometry"/></mxCell>
        <mxCell id="20" style="edgeStyle=orthogonalEdgeStyle" edge="1" parent="1" source="5" target="6"><mxGeometry relative="1" as="geometry"/></mxCell>
        <mxCell id="21" style="edgeStyle=orthogonalEdgeStyle" edge="1" parent="1" source="14" target="15"><mxGeometry relative="1" as="geometry"/></mxCell>
        <mxCell id="22" style="edgeStyle=orthogonalEdgeStyle" edge="1" parent="1" source="15" target="16"><mxGeometry relative="1" as="geometry"/></mxCell>
        <mxCell id="23" style="edgeStyle=orthogonalEdgeStyle" edge="1" parent="1" source="16" target="17"><mxGeometry relative="1" as="geometry"/></mxCell>
        <mxCell id="24" style="edgeStyle=orthogonalEdgeStyle" edge="1" parent="1" source="8" target="9"><mxGeometry relative="1" as="geometry"/></mxCell>
        <mxCell id="25" style="edgeStyle=orthogonalEdgeStyle" edge="1" parent="1" source="9" target="10"><mxGeometry relative="1" as="geometry"/></mxCell>
        <mxCell id="26" value="CQR==null" style="edgeStyle=orthogonalEdgeStyle" edge="1" parent="1" source="10" target="11"><mxGeometry relative="1" as="geometry"/></mxCell>
        <mxCell id="27" value="CQR!=null" style="edgeStyle=orthogonalEdgeStyle" edge="1" parent="1" source="10" target="12"><mxGeometry relative="1" as="geometry"/></mxCell>
      </root>
    </mxGraphModel>
  </diagram>
</mxfile>
```

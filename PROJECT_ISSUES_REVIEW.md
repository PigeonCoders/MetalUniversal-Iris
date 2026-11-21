# PROJECT_ISSUES.md 核查报告

> **核查对象**：`PROJECT_ISSUES.md`（审计时间点 `039563e`）
> **核查时 HEAD**：`478a968`（`feature/metalfx-port`，版本 1.1.10）
> **核查方式**：逐条读源码定位、跑本地门禁（`./gradlew compileJava compileTestJava test`）、核对 git 状态与 CI 产物、比对设备端真实日志
> **结论**：12 条中 **9 条成立**、**2 条部分成立**、**1 条事实错误**。已修 6 条（commit `478a968`），1 条明确不采纳，其余待决策。

---

## 一、总评

| ID | 审计主张 | 核查结论 | 处置 |
|---|---|---|---|
| ISSUE-001 | Iris 实为硬依赖，manifest 只写 `recommends` | ✅ 成立 | **已修**：`iris` 移入 `depends` |
| ISSUE-002 | README/元数据滞后 | ✅ 成立（且遗漏了一处） | **已修** |
| ISSUE-003 | 帧插值不产生双倍 present | ✅ 代码事实成立，严重度应下调 | **已修文档** |
| ISSUE-004 | motion vector 恒零、运动管线未接线 | ✅ 成立，且属**已知有意的状态** | 待决策 |
| ISSUE-005 | 能力位对 compute/SSBO/image/tess 报 false | ✅ 事实成立，**建议不改** | **不采纳**（见第四节） |
| ISSUE-006 | CI 不自动覆盖当前分支 | ⚠️ 部分成立，且含一处事实错误 | **已修触发范围**，错误已纠正 |
| ISSUE-007 | Linux 上 4 个 native 测试失败 | ✅ 成立且为既有已知基线 | 待决策 |
| ISSUE-008 | fixture 缺失静默跳过、Photon 无测试 | ✅ 成立 | 待决策 |
| ISSUE-009 | 根目录历史任务书 | ✅ 成立 | 待整理 |
| ISSUE-010 | 平台判断与日志细节 | ⚠️ 三条中只有一条是现行 bug | **已修**（三条一并加固） |
| ISSUE-011 | 过时注释 | ✅ 成立 | **已修** |
| ISSUE-012 | 分支/发布卫生 | ✅ 成立 | 部分已修，发布目标待决策 |

---

## 二、逐条核查

### ISSUE-001（高）Iris 硬依赖 —— ✅ 成立

审计定位的三个调用点全部确认存在，且都在普通渲染热路径上，没有 `isModLoaded` 保护、也没有 `try/catch NoClassDefFoundError`：

| 位置 | 代码 |
|---|---|
| `MetalCommandEncoder.java:492` | `return complementDepthClear(clearDepth, Iris.isPackInUseQuick());` |
| `MetalCompiledRenderPipeline.java:552` | `|| !Iris.isPackInUseQuick()` |
| `MetalRenderPass.java:732` | `if (Iris.isPackInUseQuick())` |
| `MetalCommandEncoder.java:762` | `Iris.getPipelineManager().getPipelineNullable()` |

`fabric.mod.json` 核查前：

```json
"depends":    { "fabricloader": "...", "minecraft": "...", "java": "...", "sodium": ">=0.9.1" },
"recommends": { "iris": "*" }
```

补充证据：`MetallumMixinConfigPlugin.java:84-90` 只对 `.mixin.iris.` 包按 Iris 存在性门控，**不会**阻止我们自己的类加载；jar 内确实不包含 `net/irisshaders` 条目。

**处置**：已把 `iris` 移入 `depends`。缺 Iris 时 Fabric 会在启动阶段给出明确错误，而不是渲染到一半 `NoClassDefFoundError`。

> 备选方案（加 `isIrisLoaded()` 缓存守卫 + 无 Iris 时按 false 处理）没有采纳：本模组是 Iris 管线的 Metal 实现，未装 Iris 的路径从未测试，声明成硬依赖更诚实。

---

### ISSUE-002（中高）文档滞后 —— ✅ 成立

逐项核对（核查前状态）：

| 文档声明 | 代码事实 |
|---|---|
| 入口 `com.metaluniversal.MetalUniversal` | `com.metallum.Metallum`（`fabric.mod.json` entrypoints） |
| 原生实现 `MetalUniversalNative.swift` | `src/main/native/MetallumNative.swift` |
| 空间超分四档（77/67/56/33） | 五档，含 `HIGH(0.85f)`（`MetalFxConfig.java:53`） |
| 帧插值"将有效帧率翻倍" | 每渲染帧只 present 一次（见 ISSUE-003） |
| 无 Iris 章节 | 只在 renderScale 互斥说明里出现过一次 |
| `contact.homepage/sources` | 指向 `EternityQwQ/metallum`（上游 fork） |

**审计遗漏一处**：同一行"四档预设"旁边还写着 "Quality / Balanced / Performance / Ultra Performance"，而代码里 `HIGH` 已排在循环首位 —— 文档与代码的档位数和顺序同时滞后。核查时一并修正。

**处置**：两份 README（英/中）已更新；新增「Iris 光影包支持」章节（依赖级别、通用规则策略、真机验证矩阵、Photon 限制、BSL 10.1.8 提示）；`fabric.mod.json` 的 `contact` 改指本 fork 并补 `issues`。

---

### ISSUE-003（中）帧插值不翻倍 —— ✅ 代码事实成立，严重度应下调

事实核对无误：

- Swift 侧只有一处 `commandBuffer.present(drawable)`（`MetallumNative.swift:1889`）
- Java 侧只有一处 `encodePresentTextureToDrawable`（`MetalCommandEncoder.java:846`）
- `MetalFxPipeline.java:413` 把 `currentFrame` 替换为 `interpolationOutputTexture`，随后只呈现这一张

**但审计没有指出**：`MetallumNative.swift:2415` 用 `#available(macOS 26.0, iOS 26.0, *)` 硬门控，测试机 iPadOS 18.4 上 `metallum_fx_supports_frame_interpolation` 恒返回 0。**该功能在真机上根本不可达**，所以这不是"用户会遇到的性能不达预期"，纯粹是文档承诺错误。

**处置**：README 的帧插值描述改为"用插值帧替换当前呈现帧、不会让刷新率翻倍、约一帧延迟、硬件路径需 macOS 26 / iOS 26"。

---

### ISSUE-004（中）motion vector 恒零 —— ✅ 成立，但属已知有意状态

核查确认：

- `MetalFxConfig` 注释自述 "motion-vector texture is currently zero-filled"
- `temporalMotionTexture` 创建时清零一次后不再写入
- `MetalEntityMotionCapture` / `MetalEntityMotionPipeline` / `MetalMotionStateStore` / `MetalMotionHooks` 四个类**零调用方**，`metallum.mixins.json` 中无对应 mixin

这与我们此前对 Temporal 的评估结论一致：补齐需要逐物体运动向量（entity replay + cutout reactive + hand overlay），成本 800–1500 行且实测收益不如 0.85× Spatial，因此**主动不做**。

审计提出"死代码看起来像已支持"这点成立。**待决策**：删除这四类，或保留并标注 `NOT WIRED`。倾向保留 + 标注（将来接 Temporal 时是现成骨架）。

---

### ISSUE-005（中）能力位报 false —— ✅ 事实成立，但**不建议修改**

代码确实如此（`IrisRenderSystemMixin.java:63-68`）：

```java
@Inject(method = {"supportsSSBO", "supportsImageLoadStore", "supportsCompute", "supportsTesselation"},
        at = @At("HEAD"), cancellable = true, remap = false)
→ cir.setReturnValue(false);
```

审计认为"后端其实已具备 compute/SSBO/storage image"，这一点**部分正确但推不出应改 true**：

- 我们具备的是**自己执行图里的** compute（Solas `shadowcomp` 走 `IrisMetalExecutionGraph.planCompute`）；
- Iris 的 `FeatureFlags` 一旦为 true，Iris 会走它自己的 `glDispatchCompute` / `glBindBufferBase` / `glBindImageTexture` 路径 —— 那些入口在本后端仍是 no-op 或未实现；
- 结果会把"被明确拒绝"变成"静默不工作"，比 fail-closed 更糟。

**处置：不采纳。** 建议在 README 兼容矩阵中写明"声明 `required` compute/SSBO/custom images 的包目前不受支持"。

---

### ISSUE-006（中）CI 不覆盖当前分支 —— ⚠️ 部分成立

**成立的**：`.github/workflows/build.yml` 只响应 `v*` tag 与 `workflow_dispatch`；`dev-ci.yml` 只响应 `feat/*`，不含 `feature/*`。当前分支 `feature/metalfx-port` 的推送确实不会自动跑。

**错误的一条**：审计称"1.1.9 目前没有对应的 CI jar（`build/ci-artifacts` 最新为 1.1.8）"。

实际存在：

```
build/ci-artifacts/debug-switches-43691078337/metallum-1.1.9.jar
SHA-256 e80d30e83f2c7f17647c55b56655bcd8edcce9a9c0cb4de153a1572b23d14b74
```

对应 CI run `43691078337`（绿）。审计大概是按目录名字母序查看，`debug-switches-4369…` 排在旧目录之后。**结论：凡是"产物缺失"类判断，应以 `gh run list` + artifact API 为准，不以本地目录排序为准。**

**处置**：`dev-ci.yml` 增加 `feature/*` 推送与 `iris-26.2-preview` 的 PR 触发；注释中的仓库地址改为本 fork。

---

### ISSUE-007（中）Linux 上 4 个 native 测试失败 —— ✅ 成立且为既有基线

本地实测：

```
185 tests completed, 4 failed, 4 skipped
```

失败的 4 个与已知基线逐条一致，根因统一为 `Missing native library resource: /natives/macos/libmetallum.dylib` → `MetalNativeBridge.<clinit>`：

- `MetalVertexInputLayoutTest.missingAttributeUsesReboundGenericInput`
- `MetalVertexInputLayoutTest.defaultBufferEncodesGlCurrentValueForEveryBaseType`
- `IrisMetalExecutionGraphTest.rasterStorageBindingsKeepLogicalSsboIdentity`
- `IrisMetalProgramFrontendTest.realBslProgramSetUsesIrisFallbackAndTransforms`

**待决策**：加 `Assumptions` 门控可以让 Linux 全绿，但会让 macOS 上"native 真的加载失败"退化为静默 skip。若要做，需同时提供 `-Dmetallum.tests.requireNative=true` 强制失败开关，并在 CI 的 macOS job 上启用。

---

### ISSUE-008（中低）fixture 缺失静默跳过 —— ✅ 成立

`build/compat-packs/` 现有 9 个包（Bliss / CR / Unbound / MakeUp / Mellow / Nostalgia / Photon / Sildur's / Solas），测试均以 `Assumptions.assumeTrue(Files.isRegularFile(PACK))` 跳过。`Photon.zip` 存在但没有 `Photon*Test`。

**说明**：这些 zip 不进仓库是**有意**的（体积 + 分发许可），因此"干净 checkout 上测试全 skip"是预期行为而非缺陷。真正需要补的是 **CI 里显式的 fixture 校验**：缺包时打一条醒目的汇总，而不是静默通过。

---

### ISSUE-009（中低）根目录历史任务书 —— ✅ 成立

`spec.md`、`tasks.md`、`checklist.md` 均为 8 月 MetalFX 三缺陷的任务书，行号与分支名已失效。其中三个缺陷在当前 HEAD 确已修复（`MetalSurface.java:194` 的 3x 钳制、`MetalFxPipeline` 的 `isNullHandle` 检查、Swift 零尺寸 guard）。

**待整理**：移入 `docs/history/` 或改名为带日期的归档文件，并在文件头标注 `COMPLETED`。

---

### ISSUE-010（低）平台判断与日志 —— ⚠️ 三条中只有一条是现行 bug

| 审计主张 | 核查 |
|---|---|
| `MetallumMixinConfigPlugin` 用 `os.name.contains("mac")`，iOS 可能不匹配 | **潜在风险，非现行故障**。设备日志显示 `os.name` 为 `Mac OS X`（Iris 打印 `OS: Mac OS X (18.4)`），判断成立。仅在某个启动器真的报 `iOS`/`Darwin` 时才失效 —— 而一旦失效，所有 mixin 关闭、后端整体 no-op，后果严重 |
| `MetalFxSystemCheck` 在 iOS 上走 else 分支打印"不是 macOS" | **不成立**：`os.name` 是 `Mac OS X`，实际走的是 macOS 分支。真机日志为 `macOS 18.4 低于帧插值最低要求 (26.0+)` —— 问题只是**措辞**把 iPadOS 说成了 macOS |
| `MetalSurface` 先覆盖再打印，日志看不到原始 0 值 | **成立**，实打实的诊断信息丢失 |

**处置**：三条一并加固 —— 平台族判断统一为 `mac/darwin/ios`；MetalFX 日志按 `MetalNativeBridge.isIOS()` 打 `iPadOS/iOS` 或 `macOS`；`MetalSurface` 改为先打日志再回退。

---

### ISSUE-011（低）过时注释 —— ✅ 成立

- `MetalComputePipeline` 类注释仍写 `-> shaderc`，而实现在 1.1.9 已改为 `GlslangBridge.compileGlslToSpv`
- `MetallumNative.swift:2334` 写 `MTLFXFrameInterpolator — macOS 14.0+ / iOS 17.0+`，而实际 `#available` 门控是 26.0

**处置**：两处已改。

---

### ISSUE-012（中低）分支/发布卫生 —— ✅ 成立

核查数据：

```
feature/metalfx-port  vs origin/iris-26.2-preview : 157 ahead, 0 behind
upstream/26.2-fabric                             : 180 ahead
tag                                              : MetalUniversal_1.0.0 ~ 1.0.4（无 1.1.x）
build.yml publish                                : project: w79ASAJD（上游 Modrinth 项目）
```

**处置**：部分已修（CI 触发范围）。**未做的两项需要拍板**：

1. 打 `1.1.10` tag —— **在 publish 目标修正之前不要打**，否则会触发向上游 Modrinth 项目发布
2. Modrinth project id 是否改为本 fork 的项目 —— 需要项目 id 与 token

---

## 三、本次已修（commit `478a968`，版本 1.1.10）

| 项 | 文件 |
|---|---|
| `iris` 移入 `depends`；contact 指向本 fork | `src/main/resources/fabric.mod.json` |
| 入口类、原生文件名、五档预设、帧插值措辞、Iris 章节、构建任务列表 | `README.md`、`README_zh-CN.md` |
| 平台族判断 `mac/darwin/ios` | `MetallumMixinConfigPlugin.java` |
| MetalFX 平台标签与版本提示 | `MetalFxSystemCheck.java` |
| 回退前打印 GLFW 原始尺寸 | `MetalSurface.java` |
| compute 注释改为 bundled glslang | `MetalComputePipeline.java` |
| 帧插值可用性注释修正 | `src/main/native/MetallumNative.swift` |
| `feature/*` 推送与 `iris-26.2-preview` PR 触发 | `.github/workflows/dev-ci.yml` |

门禁：**185 tests / 4 failed / 4 skipped**，失败集合与基线逐条一致。
产物：`metallum-1.1.10.jar`，SHA-256 `87cd4cfca619a61837d93b71733781555596f777cacc86b352a4366b48b10038`。

**这版无渲染路径改动**，画面与帧率应与 1.1.9 完全一致。

---

## 四、明确不采纳

**ISSUE-005（把能力位改成 true）** —— 理由见第二节。当前 fail-closed 是正确的：Iris 的 GL compute/SSBO 入口在本后端没有实现，报 true 只会把"明确拒绝"变成"静默不工作"。文档层面补一句限制说明即可。

---

## 五、待决策

| 项 | 选项 |
|---|---|
| ISSUE-004 | 删除 `MetalEntityMotion*` 四类死代码 / 保留并标注 `NOT WIRED`（倾向后者） |
| ISSUE-007 | Linux 测试门控（配 `-Dmetallum.tests.requireNative=true`）/ 维持现状 |
| ISSUE-008 | CI 增加 fixture 校验步骤 / 维持静默 skip |
| ISSUE-009 | 归档 `spec.md`/`tasks.md`/`checklist.md` 到 `docs/history/` |
| ISSUE-012 | 打 `1.1.10` tag（需先定 publish 目标）/ 改用 GitHub Release only / 接 Modrinth 项目 id + token |

---

## 附录：核查命令

```bash
# 依赖声明与入口
python3 -c "import json;d=json.load(open('src/main/resources/fabric.mod.json'));print(d['depends'],d['contact'])"

# Iris 直调点
grep -rn 'Iris\.' src/main/java/com/metallum/client/metal/render/{MetalCommandEncoder,MetalCompiledRenderPipeline,MetalRenderPass}.java

# 帧插值 present 次数
grep -rn 'present(drawable)' src/main/native/MetallumNative.swift
grep -rn 'encodePresentTextureToDrawable' src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java

# 能力位
sed -n '60,80p' src/main/java/com/metallum/mixin/iris/IrisRenderSystemMixin.java

# CI 触发与产物
head -20 .github/workflows/{build,dev-ci}.yml
ls build/ci-artifacts/debug-switches-43691078337/

# 分支与 tag
git rev-list --left-right --count origin/iris-26.2-preview...HEAD
git tag | tail -5

# 门禁
LIBRARY_PATH=/usr/lib/x86_64-linux-gnu ./gradlew compileJava compileTestJava test
```

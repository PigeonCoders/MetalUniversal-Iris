# MetalUniversal-Iris — 项目级 AGENTS 预设

> 本文件随仓库分发。任何 AI 会话（dsh-tui / opencode / 其他 agent）在本仓库工作前必须先读本文件。
> 目标：让 **MakeUp** 与 **Mellow** 两个 OptiFine-format 光影包在 MetalUniversal 的 Metal 后端上稳定运行。

## 0. 项目定位

- 本仓库 `PigeonCoders/MetalUniversal-Iris` 是 `EternityQwQ/MetalUniversal` 的 `feat/iris-26.2-msl-pipeline` 分支的独立开发副本（因 GitHub 单 fork 限制，新仓库不是 GitHub 标记的 fork）。
- 上游：`EternityQwQ/MetalUniversal`，分支 `feat/iris-26.2-msl-pipeline`。
- 本地 remote 约定：
  - `origin` = `git@github.com:PigeonCoders/MetalUniversal-Iris.git`（或 https）
  - `upstream` = `https://github.com/EternityQwQ/MetalUniversal.git`（同步 Iris 上游）
- 旧 1.21.11 仓库已重命名为 `PigeonCoders/MetalUniversal-1.21.11`，不要与本仓库混淆。

## 1. 版本事实（以 `gradle.properties` / `build.gradle` 为准，本文件不锁定版本）

- Minecraft `26.2`，Fabric Loader `0.19.3`，Loom `1.16-SNAPSHOT`
- Sodium `mc26.2-0.9.1-fabric`
- Iris `1.11.2+26.2-fabric`
- 子模块：`vendor/glslang` / `vendor/SPIRV-Headers` / `vendor/SPIRV-Cross`（GLSL→SPIR-V→MSL 工具链，提交锁死，勿随意升级）

## 2. 工作铁律

1. **禁止脑补**。MC 26.2 / Sodium 0.9 / Iris 1.11.2 / LWJGL / Metal / SPIRV-Cross 的 API 与语义必须查证后写：
   - `minecraft-dev-mcp`：`get_minecraft_source`（26.x Mojmap）、`analyze_mod_jar`（反编译 Iris/Sodium）、`analyze_mixin`、`compare_versions`、`find_mapping`
   - `mcmodding-mcp`：Fabric 映射与模组文档
   - `context7-mcp`：LWJGL / SPIRV-Cross / Sodium / Iris 文档
   - `java-lsp`：诊断与跳转
   - `repogate`（GitHub MCP）：查上游 PR/issue/源码
2. **先读后改**。改任何符号前，先看现有实现、mixin target 与调用方；Mixin 新增/改名必须同步 `metallum.mixins.json` 与 `MetallumMixinConfigPlugin` 白名单。
3. **版本敏感**。所有版本号以仓库 `gradle.properties` 为唯一真源；迁移/升级先跑 `compare_versions`。
4. **不确定性透明**。查不到就写“未验证”，并给出验证方法；禁止用推测当结论。
5. **参考优先级**：可编译可运行的源码 > 文档 > 推测；冲突以源码为准。
6. **目标优先**：优先让 MakeUp、Mellow 跑通跑稳；通用修复可顺带，但不得引入与两包无关的大重构。

## 3. 分支与阶段工作流

- 默认分支：`main`（开发主线，即 Iris 代码）。
- `iris`：与上游 `feat/iris-26.2-msl-pipeline` 对齐的镜像分支，只用于同步，不在其上直接开发。
- 每个阶段从 `main` 开 `feat/<pack>-<what>` 或 `fix/<pack>-<what>` 分支。
- 同步上游：`git fetch upstream feat/iris-26.2-msl-pipeline` 后显式 merge/rebase 到 `main` 并跑完整 CI，禁止 force push `main`。

## 4. 阶段门禁（必须遵守）

一个“阶段” = 一个可验证的代码完善单元（例如“Mellow 的 composite 阶段不再黑屏”、“MakeUp 的 gbuffers_water 编译通过”）。

每个阶段必须按顺序完成：

1. **本地验证**
   - `./gradlew test`（Linux 上原生/GPU 测试会自动 skip，这不代表通过，只是没跑）
   - 改 Swift 时，按本机可用工具链做 Swift 语义验证（参考 `~/Metal/AGENTS.md` 的 xtool 配方）；改 shader 编译逻辑时跑相关单元测试
2. **提交并推分支**：清晰的中文/英文 commit message，说明“改了什么 + 为什么 + 验证了什么”。
3. **开 PR 到 `main`**。
4. **必须等 GitHub CI 全绿**。CI 在 `macos-15` 上执行**含原生库的完整构建**：
   - `buildMacNative buildMacSpvc buildMacGlslang buildIOSNative buildIOSSpvc buildIOSGlslang build metalIrisTexturesIntegrationTest`
   - 产物：macOS/iOS 的 `libmetallum.dylib`、`libspvc.dylib`、`libglslang*.dylib` 等，位于 `src/main/resources/natives/`
   - CI 不绿 = 阶段未完成，禁止进入下一阶段，也禁止把“本地能编译”当通过。
5. **交给用户实机测试**：CI 绿后**停止代码工作**，把 PR 链接 + CI artifacts（或带 tag 的 release）交给用户，在真机（Apple Silicon macOS / iOS）上运行 MakeUp 或 Mellow，收集截图/日志/复现步骤。
6. **用户确认前不合并 PR**。用户反馈的问题优先于新功能；用户确认“可以/有改进但仍需修”后，才能合并或进入下一阶段。

CI 只证明“编译与测试通过”，**不证明画面正确**；画面正确性以用户实机结果为准。

## 5. 实机测试交接模板（每次阶段完成必须给全）

```
阶段名 / PR 链接：
CI 运行链接与结果：
构建产物位置（CI artifact 名 / release 链接）：
目标光影包与档位（MakeUp / Mellow，shader options）：
测试环境（macOS 机型 + MC 版本，或 iOS 设备 + 启动器）：
本次改动涉及（哪个 pass / mixin / 编译阶段）：
期望观察：
需要重点看（水面 / 阴影 / 云 / 太阳月亮 / 透明 / 闪烁 / 崩溃）：
日志收集位置与上报方式：
```

## 6. 当前已知缺口（开发方向参照，按目标包优先排序）

- geometry / tessellation shader 目前被 `ShaderCreatorMixin` 跳过（只编译 vertex+fragment）
- 独立 `GlShader` 对象尚无 GLSL→MSL 路由（`GlShaderMixin` 只返回哨兵）
- shadow mipmap 使用光栅化深度实现，fragment-written-depth 的 PSO 契约未闭合
- `MetalCrossShaderCompiler` 存在 shaderpack sampler 与 vanilla 保留纹理槽 {0,1,2} 对齐 TODO
- 只实证过 BSL / Potato；MakeUp、Mellow 是当前验收对象
- 上游默认分支已 revert Iris-on-Metal；安全/性能修复需手动 cherry-pick 到 `main`

## 7. dsh 开发环境（本机已配好）

启动方式：

```bash
cd ~/MetalUniversal-Iris
DSH_TUI_WORKSPACE_TARGET="$PWD" dst
```

已挂载 MCP：`mcp__minecraft-dev__*`、`mcp__mcmodding__*`、`mcp__context7__*`、`mcp__java-lsp__*`、`mcp__repogate__gh_*`。本仓库工作必须优先使用这些工具查证，而不是凭记忆写代码。

## 8. 参考目录

- 本机只读参考源码（浅克隆，用于对照阅读）：
  - `/home/ubuntu/projects/.research-metaluniversal/Iris-26.2`（Iris 26.2 源码）
  - `/home/ubuntu/projects/.research-metaluniversal/MetalUniversal-iris`（本仓库开发工作副本）
- 旧 1.21.11 项目与 Swift 交叉编译工具链说明：`~/Metal/AGENTS.md`

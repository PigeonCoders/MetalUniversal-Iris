**English** | [简体中文](README_zh-CN.md)

# MetalUniversal
> This project is developed from [Metallum](https://github.com/kokodio/metallum), as a fork iteration of the original project. It preserves the original Metal rendering backend capabilities while adding full support for the iOS platform.

Metallum is a Minecraft rendering backend mod (Fabric Mod) based on the Apple Metal API, designed to replace the OpenGL/Vulkan rendering path on macOS and iOS, providing more efficient GPU rendering for Apple Silicon and iOS devices.

This project is still in the experimental stage (PoC); performance and stability may vary depending on the system and installed mods.

## Architecture

| Layer | Implementation |
|-------|----------------|
| Entry point | `com.metaluniversal.MetalUniversal` (PreLaunch + ModInitializer) |
| GPU backend | `MetalBackend` → `MetalDevice` → `MetalCommandEncoder` / `MetalRenderPass` |
| Shader compiler | `MetalCrossShaderCompiler` (GLSL/SPIR-V → MSL, based on SPIRV-Cross) |
| Native bridge | `MetalNativeBridge` (Java Foreign Memory API ↔ Swift C exported functions) |
| Native implementation | `MetalUniversalNative.swift` (Metal API calls, CAMetalLayer management, inline MSL shaders) |
| Mod injection | Mixin injection into Minecraft `PreferredGraphicsApi` and Sodium rendering backend selection |

## Compatibility

- **macOS**: Apple Silicon (M1 or later), loads `libmetallum.dylib` directly through the Native Bridge
- **iOS**: iOS 14.0 or later, ships prebuilt `libmetallum.dylib` (arm64) and `libspvc.dylib` (with MSL backend) inside the jar

## 运行依赖（前置条件）

| 依赖 | 版本 | 说明 |
|------|------|------|
| Fabric Loader | >= 0.19.2 | **必需** — 模组加载器 |
| Minecraft | 26.2 | **必需** — 目标游戏版本 |
| Java | >= 25 | **必需** — 运行时环境 |
| Sodium | mc26.2-0.9.1-fabric | **必需** — 提供完整的视频设置界面适配 |

> ### 关于 Sodium
>
> 安装 Sodium 后，MetalFX 设置会作为**独立页面**自动集成到 Sodium 的视频设置界面中（与 Iris 的 "Shader Packs" 页面注册方式一致），由 Sodium 官方 `ConfigEntryPoint` API 提供稳定的 UI 集成，自动获得与 Sodium 原生选项一致的渲染、搜索索引和 tab 排序行为。
>

## MetalFX 超分辨率与帧插值

本模组集成了 Apple [MetalFX](https://developer.apple.com/documentation/metalfx) 框架，提供空间超分辨率（Spatial Upscaling）与帧插值（Frame Interpolation）两项 GPU 加速功能，可在支持的设备上显著提升帧率与画面流畅度。

### 功能说明

| 功能 | 说明 |
|------|------|
| **空间超分（Spatial Scaler）** | 在低分辨率渲染游戏画面，再用 MetalFX 专用硬件放大到原生分辨率，降低 GPU 着色负担，提升帧率。提供 Quality / Balanced / Performance / Ultra Performance 四档预设（分别对应 77% / 67% / 56% / 33% 内部渲染分辨率） |
| **帧插值（Frame Interpolator）** | 利用硬件光流估计在两帧之间合成中间帧，将有效帧率翻倍，大幅改善快速移动场景的流畅度 |

### 适配的系统与芯片

以下为 Apple 官方 MetalFX 框架的适配要求。本模组严格遵循官方支持范围，不支持硬件路径的设备将自动禁用对应功能（不会崩溃，仅静默无效）。

#### 空间超分（MTLFXSpatialScaler）

| 平台 | 系统版本 | 芯片要求 |
|------|---------|---------|
| macOS | 13.0+ | Apple GPU family 7+（M1 / A14 及以上） |
| iOS | 16.0+ | Apple GPU family 7+（A14 及以上） |

#### 时间超分（MTLFXTemporalScaler）

| 平台 | 系统版本 | 芯片要求 |
|------|---------|---------|
| macOS | 13.0+ | Apple GPU family 7+（M1 / A14 及以上） |
| iOS | 16.0+ | Apple GPU family 7+（A14 及以上） |

#### 帧插值（MTLFXFrameInterpolator，硬件加速路径）

| 平台 | 系统版本 | 芯片要求 |
|------|---------|---------|
| macOS | 26.0+ | Apple GPU family 9+（M3 及以上） |
| iOS | 26.0+ | Apple GPU family 9+（A17 Pro 及以上） |

> ⚠️ **关于不支持硬件帧插值的设备**：M1 / M2 / A14–A16 等芯片不具备 Apple GPU family 9 的硬件光流加速单元，本模组在这些设备上**不会启用**帧插值。早期版本曾使用 50/50 混合作为回退，但该方案在快速移动的第一人称视角下会产生严重拖影，效果反而不如关闭，已在当前版本移除。

### 使用方法

1. 启动 Minecraft，进入 **视频设置**
   - **安装了 Sodium**：在 Sodium 视频设置左侧 mod 列表中点击 **MetalUniversal** 图标，即可进入 MetalFX 设置页面（与 Iris "Shader Packs" 入口位置一致）,或随时按 `F8` 快捷键打开
2. 首次进入会弹出**适配警告界面**，列出上述官方系统与芯片要求，请确认您的设备满足条件
3. 点击 **"开启 MetalFX"** 进入设置界面（点击 "不开启" 则返回，下次进入仍会提示）
4. 在设置界面选择空间超分模式、时间超分模式与帧插值模式，点击 **"完成"** 保存

### 设备能力自检

设置界面会自动显示当前设备的支持情况，包括：

- GPU 设备名称
- 空间超分是否支持
- 帧插值是否支持

如果某项功能显示"不支持"，说明当前芯片或系统版本不满足 Apple 官方要求，启用该选项将不会有任何效果。

### MetalFX 与 Iris render scale（互斥说明）

> MetalFX 接管内部分辨率时（空间超分开启），`-Dmetallum.iris.renderScale`
> 会被**忽略**并记录一条 probe（"renderScale 被 MetalFX 接管"），两者不会叠加。
> MetalFX 默认全部关闭，此时 `-Dmetallum.iris.renderScale` 照常生效。

## Building

### Prerequisites

- macOS (Apple Silicon)
- Xcode (with iOS SDK, for iOS targets)
- Java 25
- Swift compiler (`swiftc`)
- CMake (builds glslang / SPIRV-Cross from the submodules)

### Submodules

The GLSL → SPIR-V → MSL toolchain is vendored as git submodules pinned to
specific commits so builds are reproducible:

| Submodule | Path | Commit |
|-----------|------|--------|
| glslang | `vendor/glslang` | `2eb8a58103d0d4d75393afef16aa9bc24d1513b0` |
| SPIRV-Headers | `vendor/SPIRV-Headers` | `29981f65241605e08b0ede4cfeb999fe3b723c6a` |
| SPIRV-Cross | `vendor/SPIRV-Cross` | `6c09849fe88c48eaed08413aa022aaa136a3a057` |

Initialize them after cloning:

```bash
git submodule update --init --recursive
```

### Build Commands

```bash
# Full build (macOS native + iOS native + iOS libspvc)
./gradlew build

# Compile macOS native dylib only
./gradlew buildMacNative

# Compile iOS native dylib only (requires Xcode + iOS SDK)
./gradlew buildIOSNative

# Compile iOS libspvc only (SPIRV-Cross MSL backend, requires Xcode + iOS SDK)
./gradlew buildIOSSpvc
```

Build artifacts:
- `src/main/resources/natives/macos/libmetallum.dylib` — macOS arm64, target 14.0
- `src/main/resources/natives/ios/libmetallum.dylib` — iOS arm64, target 14.0
- `src/main/resources/natives/ios/libspvc.dylib` — SPIRV-Cross C API (MSL backend), iOS arm64

### CI/CD

The GitHub Actions workflow (`.github/workflows/build.yml`) builds on `macos-15`, and automatically publishes to Modrinth and GitHub Releases when a `v*` tag is pushed.

## iOS Usage

1. Install the Minecraft Java Edition launcher on iOS
2. Place the jar into the Minecraft instance's `mods/` directory
3. Launch Minecraft, select "Prefer Metal" as the graphics backend in video settings, then restart the game for the change to take effect

### Notes

- `libmetallum.dylib` and `libspvc.dylib` are loaded at runtime by the launcher; no manual embedding is required
- Fabric Loader is required
- If you encounter rendering issues, try disabling other rendering-related mods first

## macOS Usage

1. Download the latest jar and place it in the `mods/` directory
2. Launch Minecraft, select "Prefer Metal" as the graphics backend in video settings, then restart the game for the change to take effect

## License

MIT License — see [LICENSE](LICENSE)
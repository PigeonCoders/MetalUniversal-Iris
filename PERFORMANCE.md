# 光影性能优化：完整过程、测量数据与待解问题

> **文档目的**：把「MetalUniversal-Iris 上光影渲染的原生性能优化」这件事从头到尾记录清楚，
> 供外部审计（DeepSeek Harness 或任何第三方）在不看代码的情况下判断：
> 我们的测量是否可信、结论是否正确、还剩哪些**我们没想到**的优化方向。
>
> **最后更新**：2026-11-21，对应 `feature/metalfx-port` @ `e7ce0a3`，版本 1.1.10
> **测试设备**：iPad Air 11" (M3) / iPadOS 18.4 / 8× CPU / Apple M3 GPU
> **运行环境**：Amethyst 启动器（自建 fork）+ MobileGlues + MoltenVK 1.2.9 / Iris 1.11.2+mc26.2 / Sodium 0.9.1 / MC 26.2

---

## 0. 一句话背景

这是一个把 **Iris 光影管线移植到 Metal** 的项目。光影包（BSL / Complementary / MakeUp / Mellow / Solas …）
在 GL 上由 Iris 生成几十个渲染 pass 和大量全屏后处理；我们把这套管线跑到 Metal 上。

性能问题很直接：**同样的包在 iPad M3 上只有 16–41 fps**，而 CPU 侧几乎空闲。
优化目标只有一个：**在不降低画质的前提下，把原生分辨率的帧时间压下去**。

---

## 1. 目标与约束

### 1.1 用户给出的目标（原话）

> 我指的「原生状态」是**在不改变任何渲染参数的情况下使算法与转移达到最优**。

也就是说：

- ✅ 要的是**算法层面**的浪费消除（重复拷贝、无用的附件读写、多余的 encoder 切换、可省的 pass）
- ❌ 不要把「降分辨率」「超分」「帧生成」当成主要答案 —— 这些是**画质换性能**，用户明确说过「我其实不太接受这样的画质」
  （它们作为可选开关保留，但不是这条线的主线）
- ✅ 每一项改动都要**默认关闭 / 开关门控**，默认行为必须与改动前逐字节一致
- ✅ 每一项都要有**可复现的测量证据**，不接受「感觉快了」

### 1.2 工程约束

| 约束 | 说明 |
|---|---|
| 不许按包名分支 | 光影包是用户自己下载的 zip，我们改不了，所有修复必须落在引擎通用规则上 |
| 不改光影包 | 任何"让包少画点东西"的方案都不算优化 |
| 默认零行为变化 | 新逻辑全部开关门控，关闭时与旧路径等价 |
| 每项要有 A/B | 通过 `-Dmetallum.iris.*` 开关对比，真机跑固定场景 |

---

## 2. 测量基建：我们怎么测的

### 2.1 探针

`-Dmetallum.perf` 打开后，每 120 帧向 `metallum-probe.txt` 追加一行摘要。
无该开关时探针完全不落盘（早期版本会无脑追加，已修）。

主要字段：

```
perf frame n=240 gpuAvg=33.7ms gpuP95=35.0 cpuAvg=2.79ms draws=184040 passCreate=5490 encoders=2120
perf stages prepare=…ms uniforms=…ms shadows=…ms finalize=…ms
perf bandwidth declared load=… store=…           # 静态模型：假设每个 pass 都整张读写
perf bandwidth encoder  load=… store=…           # 真实复用后的 encoder 口径
perf bandwidth elided   load=… store=…           # 被消除的加载/存储
perf copies total=…MB history.canon=… history.final=… depth.depthtex0=… …
perf encoders real=… clear=… blit=… reuse=… noOpen=… kind=… colorCount=… slotHandle=… depth=… clear=…
perf depth alias=on|off reason=…
```

### 2.2 口径与单位（读数据前必看）

探针的 `perf bandwidth` / `perf copies` / `perf encoders` 行都是**窗口累计值**，
不是单帧值：行首的 `w=120` 是这次窗口的帧数，后面的数字是这 120 帧的总和。

换算：

```
单帧 = 原始数字 / w
```

本文所有标注「/帧」的数字都是**已经除以 w 之后**的值；引用原始行时必须保留 `w=`。

另外三点：

- 代码里 `toMib()` 输出的是 **MiB**（1024²），本文表头写「MB」是沿用探针字面量，两者差约 4.8%；
- `perf encoders` 的 `reuse/noOpen/kind/colorCount/slotHandle/depth/clear/other` 是**按 draw 调用**计数
  （`MetalRenderPass` 每个 draw 都会取一次 encoder），不是按 pass；
  pass 数看 `perf frame` 的 `passCreate`，encoder 数看 `perf encoders real=`；
- `perf bandwidth pass=…` 也是窗口累计，**不是单帧**。

### 2.3 ⚠️ 数据可信度（重要）

**`gpuAvg` 在同一次运行内会自漂 30–88%**：同一份 40 秒 probe 里出现过
`33.7 → 49.8 → 63.2 → 59.5 ms`，而同一时间段的字节计数误差只有 **0.05%**。

因此本项目的铁律：

> **字节/结构计数用于「归因」（哪个 pass、哪次拷贝、哪次 encoder 切换）；
> 毫秒用于「判定」（是否真的变快），但必须同 session 配对 A/B、丢预热帧、看 p50/p95，
> 不能跨轮比较均值。**
> 另外：字节计数是**模型值**，稳定到 0.05% 只说明场景稳定，不代表 GPU 时间稳定，
> 更不代表真实 DRAM 流量（tile memory / 写回压缩都可能差一个量级）。

Gradle 侧的验证基线：`185 tests / 4 failed / 4 skipped`，
4 个失败固定是 Linux 缺 macOS native（`Missing native resource: /natives/macos/libmetallum.dylib`）。

---

## 3. 基线数据

### 3.1 两个代表包

| 包 | 原生帧时间 | 帧率 | 备注 |
|---|---|---|---|
| **BSL 10.1.3** | ≈ **24 ms** | ≈ 41 fps | 轻量包，更接近真实游玩场景 |
| **Complementary Reimagined 5.9.3** | **48–63 ms**（早期一次 67 ms） | 16–19 fps | 重包，全屏后处理多 |

设备分辨率 2360×1640（iOS points）。

### 3.2 分辨率与超分的实测曲线（CR）

| 配置 | gpuAvg | 帧率 | 相对原生 |
|---|---|---|---|
| 原生 1.0 | 67.0 ms | 14.9 fps | 1.00× |
| renderScale 0.75（1770×1230） | 40.6 ms | 24.6 fps | 1.66× |
| renderScale 0.6 | 28.6 ms | 35.0 fps | 2.35× |
| MetalFX Spatial QUALITY（0.77，BSL） | 24.0 → **17.8 ms** | 41 → **56 fps** | 1.35× |

`gpuAvg` 随 `renderScale²` 等比缩放 ⇒ **像素（片元）成本绝对主导**，不是顶点或 CPU。

### 3.3 BSL 的逐 pass 字节归因（单帧，由窗口总量 ÷ w 换算）

窗口内 Top-8 pass 合计 ≈ **991 MiB/帧**（encoder 口径，已除以 `w`）。占比按这个合计重算：

| pass | 流量/帧 | 占比 |
|---|---|---|
| Terrain（2 颜色 + 深度，多次 encoder） | 368 MiB | 37% |
| item_cutout（只画一只手却整屏读写） | 131 MiB | 13% |
| entity_cutout | 123 MiB | 12% |
| composite7 | 89 MiB | 9% |
| composite5 | 89 MiB | 9% |
| entity_translucent | 64 MiB | 6.5% |
| entity_solid | 64 MiB | 6.5% |
| entity_glint | 63 MiB | 6.4% |
| （以上合计） | **≈ 991 MiB** | **100%** |
| 显式拷贝（不计入上表） | 134 MiB | — |

> **口径提示**：这 8 个 pass 是 `perf bandwidth pass=` 按权重排序的前 8 名，
> 已覆盖窗口内绝大部分流量但不保证等于全量；`perf bandwidth encoder` 的**总量**
> 来自另一个窗口（923 MiB/帧），两者不要互相除。
>
> **自洽性校验**：2360×1640 的一张 RGBA16F 是 29.5 MiB，一个「4 色 + 深度」pass
> 做一次 load+store ≈ 139 MiB。`item_cutout` 实测 131 MiB/帧，与此吻合 ——
> 这也反过来说明表里的数字确实是**单帧**值，而不是把窗口总量直接抄了下来
> （若是窗口总量，131/120 ≈ 1.1 MiB，连一张附件都装不下）。

CR 的同类数据（声明口径）：

```
load = 1.25 GB/帧   store = 1.45 GB/帧   （这是静态模型，真实更低，见 §2.2/§2.3）
copies = 191 MB/帧:
  history.final 44MB  history.canon 22MB  depth.depthtex0 44MB
  depth.depthtex1 11MB depth.depthtex2 11MB shadow.depth 24MB
encoders ≈ 31 次/帧
```

### 3.4 encoder 复用归因（BSL 稳态，**按 draw 调用**计数）

```
reuse ≈ 73.6/帧   noOpen 7.1   kind 7.0   colorCount 12.0   slotHandle 4.9
depth 0   clear 0   other 0
```

> 分母是 draw 调用，不是 pass。`reuse=73.6/帧` 表示每帧约 74 次 draw 直接沿用了已开的 encoder；
> pass 与 encoder 的关系看 `passCreate≈41/帧` 与 `real≈27–31/帧`。
> `other` 是「附件动作掩码与当前 encoder 不一致」的桶（P1 load/store 消除引入的新来源），
> 实测为 0，说明当前没有自伤。

- `colorCount 12.0/帧` = **附件张数不同**（CR 的 `gbuffers_entities` 写 4 张、粒子 3 张、天空 1 张）
  → Metal 要求同一 render pass 内附件集合一致，**不改包就消不掉**，这是结构性天花板；
- `kind 7.0` = 中间插了拷贝/计算；
- `noOpen 7.1` = encoder 已被关掉；
- `slotHandle 4.9` = 张数相同但乒乓换面。

---

## 4. 已经做过的优化（按批次）

### 批次 0：测量基建（`2efa0a5`）

- 新增 `MetalPerformanceProbe`：帧时间环形样本、draw/pass/encoder/copy/uniform 计数、120 帧摘要
- `writeTimestamp` 从 CPU 计时换成真正的 GPU 时间（`GPUStartTime/GPUEndTime` downcall）
- **结果**：拿到了第一批真实数据，后续所有判断都建立在它上面

### 批次 1：CPU 侧去重（`2efa0a5`，Q1–Q3）

1. **去掉每帧重复的历史规范化**：原来 `executeFinal` 拷一次、下一帧 `beginFrame` 又拷一次同一批 target
2. **receipt 字符串延迟构造**：`receipts.enabled()` 之前不拼字符串
3. **world uniform 注册移出热路径**：每 generation 只注册/预热一次

**结果**：`gpuAvg` **完全没动**（33.7 ms 不变）。
**结论**：CPU 侧只有 3–4 ms（约 90% 空闲），这里没有帧率可捡。这是本项目第一个重要认知：
**光影在 iPad 上是纯 GPU 瓶颈**。

### 批次 2：分辨率开关（`65d8ca8`）

- `-Dmetallum.iris.renderScale=0.5..1.0`
- `-Dmetallum.iris.shadowResolution=0|256..4096`

**结果**：0.75 → 43 ms/23 fps；0.6 → 28.6 ms/35 fps（见 §3.2）。
**性质**：这是**画质换性能**，不是主线，但给出了"像素成本占比"的关键标定。

### 批次 3：带宽核算（`ba143bf`）

把"每帧读写多少 MB"从猜测变成测量：逐 pass 的 load/store 字节、拷贝按用途拆账、clear 排队 vs 真开 encoder。
**结果**：暴露了一个重要反直觉事实 —— 见下一条。

### 批次 4：P1 五项（`942c35a`）

| 项 | 做法 | 结果 |
|---|---|---|
| 附件视图缓存 | 不再每次 `new MetalGpuTextureView(t,0,1)`，改用缓存的 read/write view | **修掉一个真 bug**：句柄每次都不同 → encoder 无法复用 |
| 阴影深度双清 | 两份 shadow depth 直接 clear，不再"清一份再拷一份" | `shadow.depth` 拷贝 3840→1920 MB/120 帧 |
| 深度按需捕获 | 只在真正被采样时拷贝 | BSL 上无变化（它确实声明了 depthtex0） |
| blit 批处理 | 连续拷贝共用一个 blit encoder | 拷贝总量 191→175 MB/帧 |
| 探针双口径 | declared 与 encoder 两套 | 见 §2.3 的认知修正 |

**结果**：改动全部生效，**但 `gpuAvg` 仍是 33.7 ms**。
**结论**：这些字节在 GPU 时间上不到测量噪声。

### 批次 5：encoder 归因（`41117d2`）

把"每次新开 encoder"按原因分类（`REUSE / NO_OPEN / KIND / COLOR_COUNT / SLOT_HANDLE / DEPTH / CLEAR / OTHER`），
并 dump 前 3 帧的 pass 顺序。

**结果**：见 §3.4。关键发现是 **render pass 其实早就合并过了**——
`MetalCommandEncoder` 在附件句柄集合相同且无 clear 时复用当前 encoder，
41 个 pass 只开出 ~27–31 次 encoder。**"合并 pass"这条优化方向当场作废。**

### 批次 6：死存储消除 + 加载消除（`1270a21`）

按 Apple 官方文档做的两项：

1. **死存储消除**：如果一个颜色附件写完后再没人读，就不 `store`
2. **加载消除**：如果一个 pass 整屏覆盖某个附件，就 `loadAction = dontCare`

**结果**：

- 死存储 **恒为 0** —— 后处理链是一条链，前一个 pass 的 store 永远是后一个的 load，**结构上无存储可省**
- 加载消除只命中 `final` 一处：**29.5 MiB/帧 ≈ encoder 总量（923 MiB/帧）的 3.2%**

**结论**：这两项在一个"每个 pass 都吃上一个 pass 输出"的管线里天花板极低。
这是本项目第二个重要认知：**光影包的后处理链不是树，是一条链**。

### 批次 7：depthtex0 零拷贝别名（`23b9ac5`）

把 `depthtex0` 直接别名到原版主深度，省掉每帧 44 MB 的全屏深度拷贝。
但加了按**包声明**的门控：只要有世界程序声明 `depthtex0`，整个 generation 禁用别名。

**结果**：BSL 声明了 depthtex0（`gbuffers_weather`），**别名被禁用，零收益**；
不读 depthtex0 的包（如 CR）可省 44 MB/帧。

**为什么加这个门控**：`gbuffers_weather` 的深度附件**就是**主深度，它又去采样 `depthtex0` ——
在 Apple 的 tile GPU 上"读正在写的附件"是未定义行为（会看到 32×32 tile 拼贴状错色，
和 Sildur's 水面网格同源）。宁可不省，也不冒画面错误。

### 批次 8：MetalFX Spatial 移植（`310f5d1`，后续 `8ef0a98` 加 High 85% 档）

- Spatial 完整可用：BSL 24.0 → **17.8 ms（41 → 56 fps）**
- 带宽 ×0.57，正好等于 0.77² —— 说明内部分辨率确实降到了 77%
- Temporal 只接了骨架（jitter 没真正应用、motion vector 恒零、深度传 NULL）→ **主动不做**
- 帧生成被 `#available(macOS 26.0, iOS 26.0, *)` 硬门控，本机不可用

---

## 5. 已经排除的方向（附依据）

| 方向 | 为什么排除 |
|---|---|
| **合并 render pass** | 已经合并过了：附件集合相同 + 无 clear 就复用 encoder。`colorCount` 那 12 次/帧是"不同 pass 写不同张附件"，不改包消不掉 |
| **fast math / `MTLMathMode.fast`** | 我们的 `makeLibrary(source:options:)` 传的是 `nil`，**没有显式设过 math mode**；社区数据说 Apple silicon 默认接近 `relaxed`，`.fast` 只多"不假设 Inf/NaN"（0–5%，可能让 tone mapping 出花屏）。**未在 M3 上自测，属于「低优先但可 A/B」，不算定论** |
| **FP16 半精度** | 公开微基准显示 M1/M2 的 FP16 FMA 吞吐与 FP32 相同（256/core-cycle），**不等于** M3 也如此；且这是精度/画质折衷。**未自测，保留为待评估** |
| **function constants 特化** | Iris 本身已经用 `#ifdef` 做编译期变体，没有额外特化空间 |
| **argument buffers** | 只降低 CPU 提交开销，而我们 CPU 只占 3–4 ms |
| **`--msl-swizzle-texture-samples`** | Apple 文档明确警告 "massive impact on performance" |
| **`--msl-force-native-arrays`** | 官方说明会恶化代码生成 |
| **`memoryless` 附件** | 要求后续不再被采样；我们的 G-buffer 全都要被后处理读，用不上 |
| **RGBA16F → RG11B10F** | 画质折损未评估，且这是"改渲染参数"，不在目标内 |
| **按 load/store 差异拆/并 pass** | Apple 官方："render passes don't need to be split when only load and store actions change" |
| **per-pass GPU 计时** | 需要 native 侧 `MTLCounterSampleBuffer`，Linux 上无法编译验证，暂缺（这是唯一想补但没补的测量能力） |

---

## 6. 仍然可疑的浪费（按嫌疑排序）

这些是**尚未做**或**做了但收益不明**的项，也是希望外部审计重点看的地方：

### 6.1 高嫌疑

1. **`item_cutout` 画一只手，整屏读写 131 MiB/帧**
   —— 手持物品只覆盖屏幕很小一块，但 pass 仍然 load/store 4 张颜色 + 深度。

   ⚠️ **更正**：Metal 的 `loadAction`/`storeAction` 作用在**整张 attachment** 上，
   `scissor`/render area 只影响片元覆盖，**不会**减少 load/store 字节。所以"收窄 scissor"这条路不成立。

   唯一可行的形态是「手部单独小 target + composite 回主缓冲」：
   把整屏 load/store 换成小 target 的局部读写，再在最终合成时贴回。
   代价是多一个 pass，且必须证明手部结果不会被 TAA/后处理整屏读取（顺序安全）。

2. **Terrain 368 MB/帧、被拆成多次 encoder**
   —— 地形在 BSL 里分 solid/cutout/translucent 多段，每段整套附件重读。
   能不能靠 render pass 的 `loadAction` 差异或 render area 收窄减少重读？

3. **`depthtex0` 在声明它的包上仍是 4 次拷贝/帧（约 59 MB + 4 次 encoder 重启）**
   —— 现在被"自采样 = UB"这条安全闸门挡住了。
   是否存在**安全的**方案（例如只在自采样 pass 前做一次快照，其余 pass 复用）？

### 6.2 中嫌疑

4. **`history.final` / `history.canon` 每帧各一次全屏拷贝（44 + 22 MB）**
   —— 是否能让翻转直接落到目标面，彻底不需要拷？

5. **mipmap 链 6 次/帧** —— 能否集中到帧边界一次做完？

6. **半分辨率后处理的可行性** —— Apple 官方把"每个效果用不同分辨率"列为正规最佳实践。
   但光影包的后处理链是包自己写的全屏 shader，改分辨率等于改渲染参数（画质），
   需要判断哪些 pass 在**视觉上不可见**的降采样（例如纯 bloom/blur 链）。

7. **`colorCount` 12 次/帧** —— 结构性，但能否通过"补一张空的附件"把相邻 pass 对齐到同一附件集合，
   从而复用 encoder？代价是多写一张全屏附件，可能得不偿失。

### 6.2b 审计新增、文档原先未列的方向

11. **`untracked` 资源 + 全局 fence 是否在串行化 encoder**
    —— 我们的纹理/缓冲区都用 `hazardTrackingMode = .untracked`，靠每对 encoder 的 `waitForFence`/`updateFence` 保正确性。
    保守但可能把本可重叠的 encoder 串起来。做法：加 `tracked` vs `untracked+fence` 的 A/B 开关，默认保留旧路径。

12. **`perf encoders ... other=` 与 `loadElision=off` 的 A/B**
    —— load/store 掩码不同会导致重开 encoder（`other` 桶）。实测 `other=0`，但应在改动 P1 时持续盯这个桶。

13. **`[[early_fragment_tests]]` 注入**
    —— 对「不写深度、不用 discard」的 opaque pass，MSL 的 early fragment tests 可能改善 TBDR 的 HSR/带宽。
    需要在 SPIRV-Cross 产物上按 pass 判定后注入，并做画面验证。

14. **显式 `MTLMathMode` + M3 上的 FP16 微基准**
    —— 目前 `makeLibrary(options: nil)`；先显式设一次并 A/B，再决定要不要碰精度。

15. **shader 翻译层自身的开销**
    —— 生成的 MSL 里可能有额外 ALU：uniform 物化、sampler index 重映射、Y 翻转、深度重映射、`float↔half` 转换。
    需要对产物做指令/寄存器审计，而不是只看包源码。

### 6.3 低嫌疑

8. 全屏 quad 的顶点开销（已确认可忽略）
9. uniform 上传（75 KB/帧）
10. draw 提交（1528–1840 次/帧，CPU 侧仅 3–4 ms，已批量）

---

## 7. 架构事实（审计时需要的上下文）

1. **pass 与 encoder 不是一一对应**：`MetalCommandEncoder.renderCommandEncoder()` 在附件句柄集合相同且无 clear 时直接复用；约 41 个 pass/帧 → 27–31 次 encoder。
2. **附件都取 `readView`**：世界 pass 里"同一张纹理既当颜色附件又被采样"是**允许**的（沿袭上游 Iris 的 in-place 语义）。Apple TBDR 上这是 UB，但目前只有 Sildur's 踩到。
3. **乒乓模型**：`colortex*` 有 main/alt 两面，pass 之间按 `RasterPlan` 的 `readsFromAlt/stateAfter` 翻转；帧边界做一次 `canonicalizeHistory` 把 alt 写回 main。
4. **深度流**：主深度会被捕获成 `depthtex0/1/2`；默认路径在 `beginTranslucents` 捕获一次 depthtex1，其余按需。
5. **拷贝全部走 blit encoder**，每开一次 blit 都要关掉当前 render encoder、之后再重开并重读整组附件 → **一次拷贝的真实成本 ≈ 该 pass 整组附件的重读**，所以"减少拷贝次数"比"减少拷贝字节"更重要。
6. **全屏后处理 pass 不挂深度**（`withDepth=false`），这点是对的。

---

## 8. 给外部审计的具体问题

请针对下面每一条给判断（同意 / 反对 / 新方案），并**指出我们数据或推理里的错误**：

1. §2.2 的结论「毫秒不可信、只看字节」是否过强？有没有办法在不写 native 的前提下把 `gpuAvg` 的漂移压下去（例如固定 GPU 频率、固定场景、用 `GPUStartTime` 的分布而不是均值）？
2. §4 批次 4/6 出现了「字节显著减少但帧时间不变」。除了"带宽不是瓶颈"，还有没有别的解释（例如 tile 内带宽、写回压缩、GPU 频率调节）？
3. §6.1 的三条高嫌疑里，哪一条在你看来最值得先做？有没有我们没列出的第四种可能？
4. 对"同一个附件既当渲染目标又被采样"（§7.2），除了禁止别名，Apple 平台有没有安全的等价写法（例如显式 tile 内存回读、或用只读副本 + 一次快照）？
5. §6.2 第 6 条（部分 pass 降分辨率）在**不改变光影包源码**的前提下是否可行？如果可行，怎么界定"视觉上不可见"？
6. 我们的 `renderScale` 与 MetalFX Spatial 已经把"像素成本"这条路走到了 0.77×。在不降分辨率的前提下，BSL 的 24 ms 还有多少空间？给出你的量级估计和依据。
7. 有没有一类优化是**光影管线移植特有的**（即 GL→Metal 的语义差异导致的额外工作），我们上面完全没提到？

---

## 9. 复现测量

```bash
# 1) 换包：build/ci-artifacts/debug-switches-*/metallum-*.jar
# 2) 启动参数只加一条：
-Dmetallum.perf

# 3) 固定场景：同一存档、/time set noon、/weather clear、原地站 40 秒
# 4) 正常退出，取 metallum-probe.txt

# 关键行
grep '^perf frame n='        metallum-probe.txt   # 帧时间与计数（看 n>=240 的稳定窗口）
grep '^perf bandwidth'       metallum-probe.txt   # 三个口径
grep '^perf copies'          metallum-probe.txt   # 拷贝按用途拆账
grep '^perf encoders'        metallum-probe.txt   # 复用归因
grep '^perf depth alias'     metallum-probe.txt   # 深度别名是否生效
grep '^perf order'           metallum-probe.txt   # 前 3 帧 pass 顺序
```

开关速查：

| 开关 | 作用 |
|---|---|
| `-Dmetallum.perf` | 探针总开关 |
| `-Dmetallum.iris.renderScale=0.5..1.0` | 主渲染目标缩放 |
| `-Dmetallum.iris.shadowResolution=0\|256..4096` | 阴影分辨率覆盖 |
| `-Dmetallum.iris.depthAlias=off` | 关 depthtex0 别名 |
| `-Dmetallum.iris.deadStores=off` / `loadElision=off` | 关死存储/加载消除 |
| `-Dmetallum.iris.frameHistory=off` | 关历史翻转（A/B） |

---

## 10. 附：术语

| 术语 | 含义 |
|---|---|
| pass | Iris 的一个渲染阶段（`gbuffers_*` / `deferred*` / `composite*` / `final`） |
| encoder | Metal 的 `MTLRenderCommandEncoder`，一次开关有固定成本 |
| 声明流量 | 静态模型：假设每个 pass 都整张读写所有附件（**偏高 30–60%**） |
| encoder 流量 | 按真实复用后的 encoder 统计（**这是我们信的口径**） |
| 乒乓 / flip | `colortex` 的 main/alt 两面按 pass 的读写关系翻转 |
| 死存储 | 写完后再无人读取的附件写入 |

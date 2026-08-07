# MusicHapticsX 更新日志 (Changelog)

## v3.11.0 — Semantic Instrument Engine (2026-08-06) 【本次版本】

**核心架构重构：从"音乐驱动震动"到"音乐触觉编排"**

- **C++ DSP 层重写** (`HapticEngine.hpp`):
  - `composeHapticLayer()` 完全重写：从旧版固定 4 层比例混合（Beat 68% + Bass 16% + Texture 9% + Melody 7%）升级为 **5 通道语义合成**（Percussion / Bass Sustain / Vocal / Harmonic / Texture+Air）
  - 每个通道独立包络跟踪（Attack/Release），最终采用 **优先级掩码（Priority Masking）** 而非固定比例混合
  - 移除了无条件 `amplitude floor=8` → **Silence = 0**（消除底震根源）
  - 移除了 `beatSustainHold_`（无条件底震保持变量）
  - 新增 `vocalBandRms_`、`vocalEnvelope_`、`harmonicEnvelope_`、`smoothedAmp_` 成员变量
  - 新增单极点平滑器 `smoothedAmp_`（快攻击 α=0.55 / 慢释放 α=0.18），消除帧间跳变导致的高 Q LRA "跳跳糖"
  - 新增 `vocalProbability_` 等 7 个乐器概率变量直接驱动合成

- **硬件参数精确化** (`ActuatorProfile.kt`):
  - **OnePlus 15**：确认使用 0816 马达（瑞声科技第三代 ESA 超宽频，448mm³，共振 130Hz，瞬态振动量 +82%），Q=18，risetime=2.5ms
  - **拯救者 Y700 二代**：纠正为 0815 马达（360mm³，频宽 107-400Hz，高 Q=15），非之前误判的双马达超宽频

- **Kotlin 侧适配** (`HapticEngine.kt` / `HapticTimelineScheduler.kt`):
  - `HapticTimelineScheduler` 新增 `adaptToActuatorQ()`：根据设备 Q factor 动态调整 slew-rate（25-45）和 LPF 系数（0.25-0.50）
  - `HapticEngine` 初始化时调用 `adaptToActuatorQ(profile.actuator.qFactor)`
  - 系统启动日志、运行时日志、注释全面更新至 v3.11 描述

- **版本号**：`3.11.0` / `versionCode 430`

---

## v3.10.20 — 全系适配 + ColorOS 深度 API + 全局苹方字体 (2026-08-05)

- `DeviceProfile.kt`：新增一加 11/12/13/13T/15/Ace3 Pro/Ace3/Ace5、拯救者 Y700、小米 Ultra 等机型的多维度检测逻辑（Build.MODEL / DEVICE / BOARD）
- `ActuatorProfile.kt`：完成全系机型马达参数定义
- `VibrateProxy.kt`：新增 `primitiveLowTickSupported` / `primitiveSpinSupported` 探测标志；新增 `colorOSHapticAvailable` / `hyperOSHapticAvailable` 厂商平台检测
- 新增 `performComposition()`、`performTextureTick()`、`performImpact()`、`performRise()` 四个高级振动 API（支持 `VibrationEffect.Composition` 的 Primitive 组合）
- `VibrateProxyService.kt`：新增 `CODE_PERFORM_COMPOSITION=6` IPC 支持
- 全局字体：创建 `AppFont.kt`，将 `pingfang.ttf` 部署至 `res/font/`，批量替换 `HapticDashboardActivity.kt`（36 处）、`IOSConsole.kt`（1 处）、`RootActivationActivity.kt`（7 处）的字体引用
- 编译生成 `app-debug.apk`（versionCode 420）

---

## v3.10.19 — 一加 15 调优 + 跳跳糖修复 (2026-08-04)

- 针对一加 15（0816 马达，高 Q=16）的跳跳糖问题实施修复：
  - C++ 侧：添加 `smoothedAmp_` 一极点平滑器（快攻击 0.55 / 慢释放 0.18）
  - Kotlin 侧：`HapticTimelineScheduler` 添加 `maxSlewPerBin=40` + `smootherAlpha=0.45` 的两阶段平滑（slew-rate limiter + one-pole LPF）
  - `VibrateProxy` 深度适配：ColorOS/HyperOS 的 `primitiveLowTick` / `primitiveSpin` 原语探测
- 建立 `ActuatorProfile` 基础框架，定义 `resonanceFreq`、`qFactor`、`riseTimeMs`、`fallTimeMs`、`maxDisplacement`、`thermalResistance` 等物理参数模型

---

## v3.9.9 — 基线版本 (先前)

- 初始版本的音乐触觉引擎，包含基础的频率分析（sub/mid/texture 三段）和启发式乐器标签判断
- Kotlin 侧 `HapticComposer` 采用旧版频谱模拟（0-15=subBass, 16-47=midBass, 48-95=texture）
- 无独立触觉编排引擎，无设备能力感知渲染，无优先级仲裁机制

---

## 架构演进路线图

| 版本 | 阶段 | 核心特征 |
|------|------|----------|
| v3.9.9 | **基线** | 实时 PCM → 三段频率能量 → 启发式判断 → 固定混合 |
| v3.10.19 | **调优** | 一加 15 跳跳糖修复、Slew-rate 平滑、物理参数模型 |
| v3.10.20 | **全系适配** | 设备检测扩展、ColorOS 深度 API、全局字体 |
| **v3.11.0** | **语义引擎** | 5 通道语义合成、7 乐器概率驱动、优先级掩码、无底震、动态 Q 感知平滑 |

---

## 待办事项（v3.11 后续）

1. Kotlin 侧 `HapticComposer.kt` 完整消费 C++ 输出的 7 个乐器概率（当前仍部分依赖旧频谱分析作为备选路径）
2. `HapticTimelineScheduler` 的优先级仲裁规则与 C++ 侧 `Priority Masking` 完全对齐验证
3. 一加 15（0816）和拯救者 Y700 二代（0815）的真实设备测试反馈循环
4. 离线音乐分析（非实时 PCM）的缓存时间轴支持（已预留 `MusicStructureAnalyzer` 接口）

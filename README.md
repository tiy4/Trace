# Trace

本机行为与消费记录仪：**记账 × 应用行为统计 × 端侧屏幕理解**。所有数据留在手机上，截图不出设备。

Trace 是一个 Android 应用（Kotlin，minSdk 26 / targetSdk 35 / compileSdk 35），把三件事收进一个 App：

- **记一笔** —— 手动记账：金额、分类、备注、深夜冲动标记与消费习惯分析。
- **行为统计** —— 基于 `UsageStatsManager` 的每日屏幕时间、解锁次数、应用 Top 5；基于无障碍服务记录
  「**在哪个应用 · 在干什么**」，例：`抖音 · 换了个视频 — <视频标题>` / `微信 · 聊天` / `系统桌面 · 回到桌面`。
- **端侧屏幕理解** —— 应用切换或内容变化时自动截一帧，交给**跑在手机上的 MiniCPM-V**（llama.cpp）读屏出描述；
  界面一律显示中文应用名，不出现包名。

## 隐私设计

- 截屏与屏幕理解**只在本机跑**，不上传、不联网。
- 云端 AI 助手是**可选**的：默认走端侧模型，想用云端时由使用者自己填 API Key。
- 触发策略按应用分档（内容类 30 s / 微信 300 s / 支付类永不截屏），熄屏与游戏自动跳过；
  关键帧以 JPEG 存在应用私有目录，可一键释放模型或清空存档。

## 代码结构

```
app/src/main/java/com/trace/app/
├─ MainActivity.kt              首页：总览 / 关联洞察 / 行为监控 / 端侧屏幕理解 / 记一笔
├─ RecordsActivity.kt           流水明细
├─ BehaviorActivity.kt          行为统计（屏幕时间 · 应用内行为时间线）
├─ ChatActivity.kt              AI 助手（端侧模型 / 云端 GLM 可切换）
├─ MonitorService.kt            前台服务：UsageStats 增量采集
├─ vlm/                         无障碍截屏 · 语义探针 · 触发策略 · 关键帧 · MiniCPM-V 推理
├─ behavior/                    应用名解析（AppNames）· 在干什么（ActivityLabel）· 屏幕时间
├─ db/TraceDbHelper.kt          SQLite（records / usage_events / screen_insights / frames / content_events / activity_log）
└─ insight/InsightEngine.kt     规则版关联洞察（非模型）
```
`com/example/minicpm_v_demo/` 是 MiniCPM-V 的推理封装与 JNI 桥。

## 构建

需要：JDK 17、Android SDK（platform 35 + build-tools）、Gradle 8.10.2。

```bash
echo "sdk.dir=<你的 Android SDK 路径>" > local.properties
gradle assembleDebug        # 产物 app/build/outputs/apk/debug/app-debug.apk
```

`app/src/main/jniLibs/arm64-v8a/*.so` 是预编译好的 llama.cpp / MiniCPM-V 原生库（仅 arm64-v8a）。
模型权重不入库，按 `vlm/VlmPaths.kt` 的约定放到应用私有目录。

## 使用前提

1. **使用情况访问权限**（UsageStats）——应用时长统计。
2. **无障碍服务**——整屏截图与界面语义读取；`scripts/enable-a11y.ps1` 可用 adb 一键开启。
   注意：**每次 `adb install -r` 之后系统会关掉无障碍，需要重新开启**，否则采不到任何界面语义。
3. 端侧模型：把 MiniCPM-V 的 gguf 放进手机后，在首页「端侧屏幕理解」里加载。

## 状态

- 已实现：记账、行为统计（应用名 + 在干什么）、应用分档触发、无障碍整屏截屏、端侧 VLM 单帧理解、
  关键帧存档与熄屏批量分析、深浅两套主题 UI。
- 计划中：自动 AI 日报（定时/熄屏充电时汇总）、应用内更细粒度动作识别。

## 第三方

`app/src/main/jniLibs/` 下的原生库由 [llama.cpp](https://github.com/ggml-org/llama.cpp)（MIT）与 MiniCPM-V 源码构建；
MiniCPM 模型权重受其自身许可约束，不随本仓分发。
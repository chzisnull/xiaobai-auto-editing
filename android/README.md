# 小白自动剪辑 · Android 端侧版

Android-only 客户端。**视频分析与导出均在手机本地完成**，不依赖云端 API。

## 当前进度（v0.4 · 设备性能分档）

已实现：

- **设备性能探测**：RAM / CPU 核数 / 模拟器识别 → 高性能·均衡·省电 + **推荐识别强度**
- **识别强度**：快速 / 标准 / 精确（自动按机型限幅，避免 OOM；可手动切换）
- **顶栏无产品大标题**：展示机型摘要 + 分档切换 + 撤销/ROI（Stitch Precision Motion 风格）
- Jetpack Compose 界面（选文件/相册 / 播放 / 吸底时间轴 / 回合列表）
- **横屏双栏**：左视频 + 右审核台/列表；竖屏滚动布局
- **精剪审核台**：播放本段、上/下一段、播头设入出点、微调、拆分/合并/删除、撤销
- **球场 ROI 四点标定**；端侧 PC 规则管线（按档位调 fps/阈值/双 pass）
- 低内存音频解码（原始 float 流式重采样）；模拟器 8GB AVD 支持
- JVM 单测：filter / builder / export / AnalysisConfig

后续计划：

1. 审核状态机（通过/弃用/仅看待审）与胶片条
2. ONNX TrackNet 精确档
3. FFmpeg-kit 精确裁切与压缩档位
4. 保存到系统相册
5. 机型性能分级（快速/标准/精确）

## 环境要求

- JDK 17（推荐 Homebrew `openjdk@17`）
- Android SDK 34（本仓库 `local.properties` 默认指向 Homebrew commandlinetools）
- 真机 Android 8.0+（API 26+），建议 8GB+ 内存机型做分析  
  **或** 本机模拟器（Apple Silicon 使用 `arm64-v8a` 系统镜像）

## 本机调试（Mac 电脑）

### 1. 环境变量

```bash
cd android
source scripts/env.sh
```

脚本会设置：

- `JAVA_HOME` → Homebrew OpenJDK 17
- `ANDROID_HOME` → `/opt/homebrew/share/android-commandlinetools`
- PATH 中的 `adb` / `sdkmanager` / `emulator`

### 2. 仅跑逻辑单测（无需模拟器）

```bash
./scripts/unit-test.sh
# 或
./gradlew :app:testDebugUnitTest
```

### 3. 一键安装模拟器 AVD（首次）

约需 3–6GB 磁盘，网络下载系统镜像 5–20 分钟：

```bash
./scripts/setup-emulator.sh
```

会安装：

- `emulator`
- `system-images;android-34;google_apis;arm64-v8a`
- AVD：`Pixel_7_API_34`

### 4. 构建、安装并启动

```bash
./scripts/run-on-emulator.sh
```

步骤：开机模拟器（若无设备）→ 单测 + `assembleDebug` → `adb install` → 启动  
`icu.yuqiuyijiaren.xiaobai.debug`，并尽量推送 `uploads/m3_match.mp4` 到模拟器 Download。

也可手动：

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n icu.yuqiuyijiaren.xiaobai.debug/icu.yuqiuyijiaren.xiaobai.MainActivity
```

### 5. 真机 + 屏幕镜像（分析保真度更好）

MediaCodec 路径在真机上更接近生产环境：

```bash
brew install scrcpy   # 可选
adb devices           # USB 调试已开
./gradlew :app:assembleDebug
adb -d install -r app/build/outputs/apk/debug/app-debug.apk
adb -d shell am start -n icu.yuqiuyijiaren.xiaobai.debug/icu.yuqiuyijiaren.xiaobai.MainActivity
scrcpy                # 在电脑上看手机画面
```

## 用 Android Studio 打开

1. 打开 Android Studio → **Open**
2. 选择本仓库下的 `android/` 目录
3. 等待 Gradle Sync
4. 连接手机或启动模拟器，Run `app`

## 使用流程

1. 打开 App → **选择录像**
2. **开始识别**（本地解码音频 + 运动；可 **取消**）
3. 时间轴点选回合，可删除 / 添加 / 合并 / 拆分 / **±0.2s**
4. **导出合并视频** → 系统分享面板（文件暂存 App 缓存 `exports/`）

## 与桌面版关系

| | 桌面 / Web | Android 端侧 |
|--|------------|--------------|
| 语言运行时 | Python + FastAPI | Kotlin + MediaCodec |
| 轨迹模型 | TrackNetV3 PyTorch | 计划 ONNX（尚未接入） |
| 剪辑 | FFmpeg | MediaMuxer remux（缓冲+合并）→ 后续 FFmpeg-kit |
| 回合结果结构 | start/end/confidence | 同语义 `Rally` 模型 |

## 包名

- release: `icu.yuqiuyijiaren.xiaobai`
- debug: `icu.yuqiuyijiaren.xiaobai.debug`

# 小白自动剪辑 · Android 端侧版

Android-only 客户端。**视频分析与导出均在手机本地完成**，不依赖云端 API。

## 当前进度（v0.1）

已实现：

- Jetpack Compose 界面（选视频 / 播放 / 时间轴 / 回合列表）
- **端侧启发式分析**：本地解码音频 → 击球峰检测 → 严格回合分组
- **端侧导出骨架**：按回合时间片 remux 合并 MP4 到 App 缓存
- 时间轴双指缩放 + 横向滑动（Compose）

后续计划：

1. 接入 ONNX TrackNet / 球场 ROI 运动（对齐桌面精度）
2. FFmpeg-kit 精确裁切与压缩档位
3. 保存到系统相册、分享面板
4. 后台分析与机型性能分级（轻量/标准/精确）

## 环境要求

- Android Studio Hedgehog 或更新版本
- JDK 17
- Android SDK 34
- 真机 Android 8.0+（API 26+），建议 8GB+ 内存机型做分析

## 用 Android Studio 打开

1. 打开 Android Studio → **Open**
2. 选择本仓库下的 `android/` 目录
3. 等待 Gradle Sync
4. 连接手机，开启 USB 调试
5. Run `app`

## 命令行构建（需本机已配置 SDK）

```bash
cd android
# 如无 wrapper jar，先用 Android Studio 同步一次生成
./gradlew :app:assembleDebug
```

安装包输出：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

环境变量示例：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || echo /opt/homebrew/opt/openjdk@17)
export ANDROID_HOME=$HOME/Library/Android/sdk
export PATH="$PATH:$ANDROID_HOME/platform-tools"
```

## 使用流程

1. 打开 App → **选择录像**
2. **开始识别**（本地解码音频，稍等进度）
3. 在时间轴点选回合，可删除 / 在播放头添加
4. **导出合并视频**（写入 App 缓存目录）

## 与桌面版关系

| | 桌面 / Web | Android 端侧 |
|--|------------|--------------|
| 语言运行时 | Python + FastAPI | Kotlin + MediaCodec |
| 轨迹模型 | TrackNetV3 PyTorch | 计划 ONNX（尚未接入） |
| 剪辑 | FFmpeg | MediaMuxer remux → 后续 FFmpeg-kit |
| 回合结果结构 | start/end/confidence | 同语义 `Rally` 模型 |

## 包名

`icu.yuqiuyijiaren.xiaobai`

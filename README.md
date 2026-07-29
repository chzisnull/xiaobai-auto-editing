# 羽毛球比赛视频自动剪辑系统 (Badminton Auto-Editing)

自动识别羽毛球比赛视频中的“发球”到“球落地/停顿”回合（Rally），剪辑裁掉冗余画面，并提供 H5 交互界面与自定义视频压缩功能。

## 项目结构

- [`badminton_auto_editing_solution.md`](./badminton_auto_editing_solution.md) - 完整技术架构方案与系统设计说明文档
- `backend/` - Python（FastAPI + 音视频分析 + FFmpeg）回合识别与剪辑处理后端
- `frontend/` - 响应式 H5 前端界面 (支持视频上传、AI识别切片时间轴可视微调、压缩导出)
- `desktop/` - macOS / Windows 桌面壳
- `android/` - **Android 端侧 App**（本地分析 + 本地导出；本机 Mac 模拟器调试见 [`android/README.md`](android/README.md) 与 `android/scripts/`）

## 快速开发与试运行

### 1. 安装依赖并启动服务
```bash
cd /Library/meibaoProject/badminton-family/badmintonMatchAutoEditing
python3 -m venv .venv
.venv/bin/pip install -r backend/requirements.txt
.venv/bin/python -m backend.app.main
```

打开 `http://localhost:8000/app/` 即可使用完整 H5 工作台。

## 桌面版（macOS / Windows）

桌面版会自动启动本地服务并在原生窗口中打开工作台，不需要手动运行 Uvicorn。完整构建说明见 [`desktop/README.md`](desktop/README.md)。

```bash
.venv312/bin/pip install -r desktop/requirements.txt
.venv312/bin/python -m desktop.main
```

macOS 使用 `desktop/build_macos.sh` 生成 `.app`；Windows 使用 `desktop/build_windows.ps1` 生成 `.exe`。桌面模式的上传副本会在一次性导出传输结束后删除，用户选择的原始文件不会被修改。

版本标签 `v*` 会通过 GitHub Actions 自动创建 Release，并附带 Windows `Setup.exe` 与 macOS `.dmg` 安装包。

## 回合识别流程

1. 上传录像后，在画面上拖动四个角点，仅框住需要剪辑的目标球场。
2. 点击“开始识别”。检测器将 2kHz-6kHz 击球声、球场多边形内的局部运动与 TrackNetV3 羽毛球轨迹结合，向前回溯到发球动作启动，向后保留落地轨迹。
3. 低置信度回合会以琥珀色显示，可在时间轴中调整或删除后再导出。

已有任务可通过 `http://localhost:8000/app/?task={task_id}` 恢复；增加 `&calibrate=1` 可直接打开球场边界重新识别。TrackNetV3 仅分析候选回合首尾的短窗口，只有明确位移的轨迹才会扩展切点；模型不可用或置信度不足时自动退回场内运动结果。低置信度切点仍建议人工复核。

TrackNet 可通过 `TRACKNET_ENABLED=false` 关闭，使用 `TRACKNET_MODEL_PATH` 指定权重，或用 `TRACKNET_DEVICE=cpu|mps|cuda` 选择设备。默认权重及 MIT 许可说明见 `backend/model_assets/README.md`。

## 导出与存储策略

`POST /api/v1/video/export/{task_id}` 会在系统临时目录完成剪辑编码，并直接返回 MP4 或 ZIP 文件。浏览器使用一次性的 Blob URL 提供下载；响应传输结束后，服务端立即删除临时目录，不保存成片，也不创建导出任务记录。

响应头包含 `X-Export-Filename`、`X-File-Size-MB` 和 `Cache-Control: no-store`。

`1080p` 和 `720p` 是输出尺寸上限，小于该尺寸的源视频不会被放大。软件编码默认使用 `libx264` / `libx265`；仅在设置 `FFMPEG_HWACCEL=true` 时尝试硬件编码。

## 测试

```bash
.venv/bin/python -m pytest -q
```

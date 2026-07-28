# 小白自动剪辑桌面版

桌面版会在 `127.0.0.1` 启动本地 FastAPI 服务，并使用系统 WebView 显示剪辑工作台。视频、数据库和临时导出文件只保存在当前电脑的应用数据目录：

- macOS: `~/Library/Application Support/XiaobaiAutoEditing`
- Windows: `%LOCALAPPDATA%\\XiaobaiAutoEditing`

导出成功后，桌面模式会删除上传到应用数据目录的原视频副本；用户原始文件不会被改动。

## 开发启动

```bash
.venv312/bin/pip install -r desktop/requirements.txt
.venv312/bin/python -m desktop.main
```

macOS 需要通过 Homebrew 安装 FFmpeg：

```bash
brew install ffmpeg
```

Windows 需要将 FFmpeg 的 `bin` 目录加入 `PATH`。若使用 NVIDIA 显卡，可设置 `TRACKNET_DEVICE=cuda`；没有显卡时会自动使用 CPU。

## 构建

必须在目标系统上构建：macOS 生成 `.app`，Windows 生成 `.exe`。

```bash
# macOS
bash desktop/build_macos.sh

# Windows PowerShell
.\desktop\build_windows.ps1
```

输出在 `dist/`。首次打开未签名的 macOS 应用时，可在 Finder 中按住 Control 点击应用并选择“打开”。

## 安装包

macOS 构建完成后可生成 DMG：

```bash
bash desktop/package_macos_dmg.sh
```

Windows 构建完成后，使用 Inno Setup 6 编译 `desktop/windows_installer.iss`，生成单个 `Setup.exe`。GitHub Release 工作流会自动完成这两步；Windows 发布包还会内置 FFmpeg 和 FFprobe。

推送 `v*` 标签会触发 `.github/workflows/release.yml`，发布产物包括：

- `XiaobaiAutoEditing-<version>-Windows-Setup.exe`
- `XiaobaiAutoEditing-<version>-macOS-<arch>.dmg`

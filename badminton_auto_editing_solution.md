# 羽毛球比赛视频自动剪辑系统 (Badminton Auto-Editing System) 完整设计方案

## 1. 方案概述

羽毛球比赛过程中，实际打球回合（Rally）时间仅占整场比赛时间的 30%~45%，大量时间被发球准备、捡球、擦汗、球员擦球拍等无用画面占据。
本系统旨在实现：**自动识别“发球 -> 击球回合 -> 球落地/违规停顿结束”**，裁切掉多余垃圾画面，并提供 **H5 可视化交互** 与 **视频自定义压缩导出** 功能。

---

## 2. 整体系统架构 (System Architecture)

系统采用 **前后端分离 + 音视频多模态 AI 分析 + FFmpeg 渲染流水线** 的混合架构：

```
 +-----------------------------------------------------------------------+
 |                            H5 前端 (Web UI)                           |
 |  - 视频上传 / 拖拽播放                                                 |
 |  - AI 分析进度展示                                                     |
 |  - 时间轴切片交互微调 (发球缓冲 / 落地缓冲)                            |
 |  - 压缩质量与分辨率设置 (1080p/720p, CRF/码率)                         |
 +-----------------------------------------------------------------------+
                                    | (RESTful API / WebSockets)
                                    v
 +-----------------------------------------------------------------------+
 |                     Python 后端服务 (FastAPI)                          |
 |  +-----------------------------------------------------------------+  |
 |  | 1. API 网关 & 异步任务调度 (BackgroundTasks / Task Queue)       |  |
 |  +-----------------------------------------------------------------+  |
 |  | 2. 多模态 AI 回合识别引擎 (Audio-Visual Rally Detector)          |  |
 |  |    - 音频击球声脉冲检测 (Audio Spectrogram Peak Detector)       |  |
 |  |    - 画面运动量与姿态变动分析 (Frame-Diff / YOLOv8 Motion)      |  |
 |  |    - 回合状态机 (Rally State Machine)                            |  |
 |  +-----------------------------------------------------------------+  |
 |  | 3. FFmpeg 视频剪辑与压缩引擎 (FFmpeg Pipeline)                 |  |
 |  |    - 准精确帧切片 (Frame-accurate cutting)                      |  |
 |  |    - 自定义缓冲补充 (+1s / +1.5s)                               |  |
 |  |    - 多段无缝拼接与 H.264/H.265 编码压缩                           |  |
 |  +-----------------------------------------------------------------+  |
 +-----------------------------------------------------------------------+
```

---

## 3. AI 回合自动识别算法方案 (Audio-Visual Hybrid Engine)

由于羽毛球飞行速度极快、球体积小且环境光线多变，单纯依赖计算机视觉检测羽毛球（如 TrackNet）虽然精度高，但对 GPU 算力要求极高且处理耗时长。
**音视频多模态分析 (Audio-Visual Hybrid Method)** 是目前性价比最高、计算速度最快（可在 CPU 上实现实时处理）的落地方案。

### 3.1 声音击球脉冲检测 (Audio Peak & Pattern Recognition)
羽毛球被球拍击中时（Smash, Clear, Drive）会发出频段集中在 **2kHz ~ 6kHz** 的高频短促碰撞声（Crisp Impact Sound）：
1. **音频提取**：使用 FFmpeg 快速提取视频的 16kHz/44.1kHz 单声道 WAV 音频。
2. **带通滤波 (Bandpass Filtering)**：使用 Bandpass Filter 过滤低频背景噪音（人声、空调声、脚步声），保留 2kHz ~ 6kHz 频段。
3. **能量峰值检测**：计算短时能量 (Short-Time Energy, STE) 和 频谱包络 (Spectral Envelope)，查找冲击峰值。
4. **击球密度阈值**：当连续 2 秒内出现多个符合时间间隔（0.2s ~ 2.0s）的峰值时，判定为正在进行回合击球 (In-Rally)。

### 3.2 目标球场运动验证 (Court-Aware Motion Filtering)
针对声音检测可能被现场加油声/隔壁球场击球声误导的问题，结合视觉分析进行二次校验：
1. **四点球场 ROI**：上传后由用户拖动四个角点标定目标球场，坐标归一化后随分析请求提交。
2. **场内运动量 (Court Motion Energy)**：对降采样帧做帧差和形态学去噪，只统计 ROI 多边形内的变化像素比例。音频击球候选必须得到局部场内运动支持。
3. **动态置信度**：根据回合击球次数和局部运动强度计算；单次击球的发球失误只在场内活动足够强时保留。
2. **状态机判定 (Rally State Machine)**：

```
  [闲置/捡球/准备] ---> (音频击球峰值 + 运动量上升) ---> [发球/回合开始 (Serving)]
                                                             |
                                                             v
  [回合结束 (Rally Ended)] <--- (音频无连续击球 > 2.5s) <--- [回合持续中 (In-Rally)]
```

4. **回合边界**：从首个有效击球向前最多回溯 2.5 秒，定位场内运动的启动点并额外保留 0.35 秒，避免轻发球声漏检后从接发球开始剪。最后一个击球后默认保留 1.4 秒，再由 TrackNetV3 的移动轨迹细化首尾。前端额外导出缓冲默认为 0。

5. **TrackNetV3 轨迹细化**：使用论文实现的官方 TrackNetV3 权重，仅对每个候选回合首尾约 3 秒窗口推理。检测点必须形成具有明确路径长度和位移的轨迹簇，静止背景误检不参与扩展切点。独立回合间保留至少 0.6 秒间隔，避免落地延伸导致误合并。

> 当前 V3 已接入羽毛球轨迹模型，但这类低机位、多球场、人员遮挡视频仍可能产生轨迹丢失。模型不确定时会优先多保留发球/落地画面，而不是冒险截断回合。

---

## 4. 视频剪辑与压缩方案 (FFmpeg Pipeline)

### 4.1 视频剪辑与无缝合并
后端拿到切片列表 `[(start_1, end_1), (start_2, end_2), ...]` 后：
1. **生成 FFmpeg concat 规则文件**，指定准确的时间段。
2. 使用 FFmpeg 重新编码（或 keyframe seek 软解）保证拼接点无卡顿、无音画不同步。

### 4.2 视频压缩策略 (Compression Options)
为了适应移动端 H5 快速预览和节省存储，系统提供 3 种预设压缩档位：

| 压缩模式 | 分辨率 (Resolution) | CRF 质量系数 | 预估体积变化 | 适用场景 |
| :--- | :--- | :--- | :--- | :--- |
| **原画精剪 (Original High)** | 维持源视频 (如 4K/1080p) | CRF 20 / Copy | 原视频 35% (仅剪掉废帧) | 电脑端保存/高清复盘 |
| **标准推荐 (Balanced)** | 最大 1080p (1920x1080) | CRF 24 | 依内容而定 | 微信/社交平台分享 |
| **极轻流畅 (Mobile Fast)** | 最大 720p (1280x720) | CRF 28 | 依内容而定 | 手机 H5 快速在线预览 |

*1080p/720p 为尺寸上限，不放大较小的源视频。默认使用 `libx264` / `libx265` 保证 CRF 行为可预期；仅在 `FFMPEG_HWACCEL=true` 时尝试硬件编码。*

---

## 5. 前端 H5 交互界面设计

H5 端采用 responsive HTML5 / TailwindCSS 设计，适配手机端与 PC 浏览器：

1. **视频导入与上传**：
   - 拖拽/点击上传比赛视频。
   - 页面实时显示上传进度条与后端 AI 处理状态（音频提取中 -> 击球识别中 -> 时间轴生成）。
2. **可视时间轴与切片列表 (Visual Segment Timeline)**：
   - 在视频播放器下方渲染 AI 提取出的有效回合（绿色高亮块）。
   - 用户可手动调整每个 Rally 的起止点、微调缓冲秒数、或一键删除“误判切片”。
3. **导出参数面板 (Export Config Modal)**：
   - 导出模式：`[一键合并成单视频]` 或 `[按分段导出 ZIP]`
   - 画质与压缩率：`原画` / `1080p 推荐` / `720p 轻量`
   - 发球前缓冲：`0.5s` / `1.0s` / `2.0s`
   - 落地后缓冲：`1.0s` / `1.5s` / `2.5s`
4. **一次性导出与下载**：浏览器接收 MP4/ZIP 响应并创建本地 Blob 下载地址，服务端传输完成后删除临时成片。

---

## 6. 关键 API 接口设计 (RESTful Specification)

### 6.1 POST `/api/v1/video/upload`
- **功能**：上传源视频文件。
- **返回**：`{ "task_id": "string", "video_url": "string", "duration": float }`

### 6.2 POST `/api/v1/ai/analyze/{task_id}`
- **功能**：触发 AI 音视频多模态分析。
- **请求体（可选）**：`{ "court_roi": [{ "x": 0.20, "y": 0.34 }, { "x": 0.82, "y": 0.34 }, { "x": 0.98, "y": 0.96 }, { "x": 0.02, "y": 0.96 }] }`
- **返回**：`{ "status": "processing" }`

### 6.3 GET `/api/v1/video/task/{task_id}`
- **功能**：读取已上传任务的文件名、原视频 URL、时长和状态，用于前端 `?task={task_id}` 恢复。

### 6.4 GET `/api/v1/ai/result/{task_id}`
- **功能**：查询 AI 识别生成的时间轴切片。
- **返回**：
```json
{
  "status": "completed",
  "total_rallies": 12,
  "original_duration": 1800.5,
  "edited_duration": 650.0,
  "rallies": [
    { "id": 1, "start": 42.5, "end": 58.0, "score_guess": null },
    { "id": 2, "start": 75.2, "end": 92.1, "score_guess": null }
  ]
}
```

### 6.5 POST `/api/v1/video/export/{task_id}`
- **功能**：提交最终剪辑与压缩参数并渲染导出。
- **请求体**：
```json
{
  "rallies": [
    { "start": 41.5, "end": 59.5 },
    { "start": 74.2, "end": 93.6 }
  ],
  "export_type": "merged",
  "resolution": "1080p",
  "codec": "h264",
  "crf": 24,
  "pre_buffer": 1.0,
  "post_buffer": 1.5
}
```
- **返回**：文件响应。`merged` 为 `video/mp4`，`zip` 为 `application/zip`。
- **响应头**：`X-Export-Filename`、`X-File-Size-MB`、`Cache-Control: no-store`。
- **存储策略**：仅在系统临时目录渲染，传输结束后立即删除；不生成持久化下载地址，不写入导出任务记录。

---

## 7. 代码架构与目录规范

```
badmintonMatchAutoEditing/
├── badminton_auto_editing_solution.md # 本设计方案文档
├── backend/                           # Python 后端
│   ├── app/
│   │   ├── api/                       # REST API 路由
│   │   ├── core/                      # 配置与常量
│   │   ├── detectors/                  # 可替换的回合检测器
│   │   ├── services/                   # 上传、分析、导出与 FFmpeg 服务
│   │   └── main.py                    # FastAPI 入口
│   └── requirements.txt               # 后端依赖包
├── frontend/                          # 由 FastAPI 托管的响应式 H5
│   └── index.html                     # 上传、播放器、时间轴与导出工作台
├── tests/                             # API、检测器和 FFmpeg 自动化测试
└── README.md                          # 项目运行说明
```

---

## 8. 项目部署与环境准备

1. **依赖环境**：
   - Python 3.9+
   - FFmpeg (必须在系统 PATH 中，或配置可执行路径)
   - 现代浏览器（前端为静态 H5，无需 Node.js 构建）
2. **快速启动**：
   - 在项目根目录执行：`pip install -r backend/requirements.txt && python -m backend.app.main`
   - 前端由 FastAPI 托管，访问：`http://localhost:8000/app/`

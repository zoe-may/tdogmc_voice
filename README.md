# tdogmc_voice

基于 Minecraft Forge 1.20.1 的轻量级音频流式传输模组。

本模组允许服务端直接向客户端推送音频流，无需客户端下载资源包。音频数据通过网络包分块传输，并由客户端的 OpenAL 引擎实时解码渲染。

## 核心特性

*   **无资源包依赖**：直接读取服务端 `sounds/` 目录下的文件。
*   **流式传输 (Streaming)**：采用分块数据传输，支持长音频播放，显著降低客户端内存占用。
*   **热重载 (Hot-Swap)**：监听文件系统事件，新增音频文件无需重启服务器即可使用。
*   **3D 空间音效**：
    *   支持定点播放 (Fixed Position)。
    *   支持实体跟随 (Entity Following)。
    *   支持自定义距离衰减、音量及音调 (Pitch)。
*   **格式支持**：`.ogg`。

## 安装说明

1.  **依赖**：Minecraft Forge 1.20.1。
2.  **部署**：将 jar 包放入服务端及客户端的 `mods` 目录。
3.  **目录**：首次启动后，在根目录生成 `sounds` 文件夹，将音频文件放入此处。

## 指令手册

所有指令需 OP 权限 (Level 2)。

### 1. 播放音频
语法：`/tdvoice play <filename> <targets> [source] [volume] [pitch] [range]`

**参数说明：**
*   `filename`: `sounds` 目录下的文件名 (包含后缀)。
*   `targets`: 接收音频的玩家选择器。
*   `volume`: 音量 (默认 1.0)。
*   `pitch`: 音调 (默认 1.0)。
*   `range`: 最大听力距离 (默认 64.0)。

**示例 A：在玩家当前位置播放**
```mcfunction
/tdvoice play test.ogg @a
```

**示例 B：定点播放 (坐标)**
```mcfunction
# 在 0, 100, 0 播放，音量 1.0，音调 0.8，范围 50 格
/tdvoice play music.ogg @a 0 100 0 1.0 0.8 50
```

**示例 C：跟随实体播放**
```mcfunction
# 声音跟随最近的僵尸移动
/tdvoice play effect.ogg @a entity @e[type=zombie,limit=1] 2.0 1.0 32
```

### 2. 停止播放
停止目标玩家当前正在接收的所有音频流。
```mcfunction
/tdvoice stopall @a
```

## 技术细节

*   **传输协议**：自定义 Forge 网络包，单包 Payload 限制为 8KB。
*   **客户端引擎**：
    *   使用 LWJGL OpenAL 进行底层音频渲染。
    *   使用 STBVorbis 进行 OGG 软解码。
    *   采用堆外内存 (Off-heap Memory) 管理缓冲区，防止 GC 压力。
*   **注意事项**：由于采用实时 PCM/Vorbis 流传输，播放高码率音频给大量在线玩家时，请监控服务器上行带宽占用。

## 配置

配置文件：`config/tdogmc_voice-server.toml`

| 配置项 | 类型 | 默认值 | 说明 |
| :--- | :--- | :--- | :--- |
| `debugMode` | Boolean | `false` | 开启后输出详细的解码与网络包日志 |
| `logBasicInfo` | Boolean | `true` | 记录音频开始/结束的基本信息 |

## 🤖 开发声明与鸣谢

本项目采用 **AI 氛围编程 (AI-Vibe Coding)** 模式开发，**90% 的代码由 AI 撰写完成**。

特别鸣谢：
*   **Google**
*   **Gemini 3 Pro**

## 许可证

本项目采用 [GNU General Public License v3.0 (GPLv3)](LICENSE) 开源。

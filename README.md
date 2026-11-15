# 龙娘の乐器 | LongNiang Instrument

## 中文

龙娘の乐器是面向 Android 的离线节奏游戏练习工具。它让玩家从曲中选取难点反复练习，无需每次重玩全曲。

- 通过系统文件选择器导入本地 SUS 曲包，按难度游玩整首谱面。项目不分发真实谱面或曲包，请自行导入有合法使用权的文件。部分 SUS 方言或节点组合可能不兼容。
- 支持多指与 Tap、Flick、Hold、Slide、Trace。Guide 提供视觉提示。音乐用 AudioTrack 输出，打击音效由独立 PCM 混音器处理。
- 自选 `[A,B)` 片段支持预览和快速重试，到 B 停止。固定循环使用独立的练习模式。
- `app` 模块负责 Android 界面、谱面加载、输入和音画同步。`judgment-poc` 提供可对照的独立判定实现。游戏时间根据音频呈现时钟推进。

**安装与使用。** Android 8.0（API 26）及以上。从 [v1.0.0 Release](https://github.com/girardotimene619-ui/LongNiang-Instrument/releases/tag/v1.0.0) 下载签名 APK，然后用 Android 安装程序打开。进入曲库导入包含 SUS 和其匹配音乐的 ZIP，选择难度后可进入全曲或片段练习。覆盖安装需要与已安装版本使用相同签名。

**构建状态。** 仓库包含应用所需的运行资源。安装与项目配置匹配的 Android SDK 和 Gradle JDK 后，可用 Gradle Wrapper 运行 `:app:assembleRelease`。发布签名由维护者在本机配置。

**后续维护。** 扩充 SUS 语义覆盖，使用可分发样本复核多指、滑动、音画同步和片段边界。

## English

LongNiang Instrument is an offline Android rhythm game practice tool. It was built so players can repeat difficult sections without replaying an entire song.

- Import local SUS chart packages through Android's file picker and choose a difficulty for full-song play. Real charts and song packs are excluded. Import only files you have permission to use. Some SUS dialects and note combinations are unsupported.
- Supports multitouch, Tap, Flick, Hold, Slide and Trace. Guide notes provide visual cues. AudioTrack plays music and a separate PCM mixer handles hitsounds.
- Preview any practice interval `[A,B)` and retry it quickly. A custom interval stops at B. The fixed-loop practice mode is separate.
- The `app` module handles Android UI, chart loading, input and audiovisual synchronization. `judgment-poc` contains a separate judgment engine for comparison. Gameplay timing follows the audio presentation clock.

**Installation and use.** Android 8.0 (API 26) or later. Download the signed APK from the [v1.0.0 Release](https://github.com/girardotimene619-ui/LongNiang-Instrument/releases/tag/v1.0.0) and open it with Android's installer. In the library, import a ZIP with the SUS file and its matching audio, select a difficulty, then choose full-song or segment practice. Updating an existing installation requires a matching signing certificate.

**Build status.** Runtime resources are included. With the Android SDK and Gradle JDK required by the project, use the Gradle Wrapper to run `:app:assembleRelease`. Release signing is configured locally by the maintainer.

**Maintenance.** Expand SUS compatibility and validate multitouch, slide handling, audio synchronization and segment boundaries with redistributable fixtures.

## 许可证 / License

当前没有项目许可证。源码可浏览，公开可见本身不授予使用、修改或再分发许可。

No project license has been selected. Public visibility alone does not grant permission to use, modify or redistribute the source.

# 龙娘の乐器 | LongNiang Instrument

## 中文

龙娘の乐器是面向 Android 的离线节奏游戏练习工具。它让玩家从曲中选取难点反复练习，无需每次重玩全曲。

- 通过系统文件选择器导入本地 SUS 曲包，按难度游玩整首谱面。项目不分发真实谱面或曲包，请自行导入有合法使用权的文件。部分 SUS 方言或节点组合可能不兼容。
- 支持多指与 Tap、Flick、Hold、Slide、Trace。Guide 提供视觉提示。音乐用 AudioTrack 输出，打击音效由独立 PCM 混音器处理。
- 自选 `[A,B)` 片段支持预览和快速重试，到 B 停止。固定循环使用独立的练习模式。
- `app` 模块负责 Android 界面、谱面加载、输入和音画同步。`judgment-poc` 提供可对照的独立判定实现。游戏时间根据音频呈现时钟推进。

**安装与使用。** Android 8.0（API 26）及以上。取得获准分发的签名 APK 后，用 Android 安装程序打开。进入曲库导入包含 SUS 和其匹配音乐的 ZIP，选择难度后可进入全曲或片段练习。本仓库的首次源码发布不提供安装包。

**构建状态。** 当前快照不具备完整构建条件。需补齐下表所列获准公开的资源。完整工程使用 Gradle Wrapper、匹配项目配置的 Android SDK 和 Gradle JDK，并通过 `:app:assembleRelease` 构建。

**后续维护。** 扩充 SUS 语义覆盖，使用可分发样本复核多指、滑动、音画同步和片段边界。

## English

LongNiang Instrument is an offline Android rhythm game practice tool. It was built so players can repeat difficult sections without replaying an entire song.

- Import local SUS chart packages through Android's file picker and choose a difficulty for full-song play. Real charts and song packs are excluded. Import only files you have permission to use. Some SUS dialects and note combinations are unsupported.
- Supports multitouch, Tap, Flick, Hold, Slide and Trace. Guide notes provide visual cues. AudioTrack plays music and a separate PCM mixer handles hitsounds.
- Preview any practice interval `[A,B)` and retry it quickly. A custom interval stops at B. The fixed-loop practice mode is separate.
- The `app` module handles Android UI, chart loading, input and audiovisual synchronization. `judgment-poc` contains a separate judgment engine for comparison. Gameplay timing follows the audio presentation clock.

**Installation and use.** Android 8.0 (API 26) or later. Open an authorized signed APK with Android's installer. In the library, import a ZIP with the SUS file and its matching audio, select a difficulty, then choose full-song or segment practice. This initial source publication has no installer.

**Build status.** The source snapshot requires the cleared resources listed below. A complete project builds with the Gradle Wrapper, the Android SDK and Gradle JDK required by the project, and `:app:assembleRelease`.

**Maintenance.** Expand SUS compatibility and validate multitouch, slide handling, audio synchronization and segment boundaries with redistributable fixtures.

## 缺失资源 / Missing resources

| 路径 / Path | 用途 / Purpose |
| --- | --- |
| `app/src/main/res/raw/` | 正式采样和备用打击音 / Primary and fallback hitsound samples |
| `app/src/main/res/drawable-nodpi/`, `app/src/main/res/mipmap-*/` | 启动动画帧和图标位图 / Launch animation frames and launcher icon bitmaps |
| `app/src/main/assets/theme/` | 界面主题图片和菜单媒体 / UI artwork and menu media |
| `app/src/main/assets/combo-provenance.tsv` | 运行时谱面来源映射 / Runtime chart provenance table |

当前没有项目许可证。源码可浏览，公开可见本身不授予使用、修改或再分发许可。

No project license has been selected. Public visibility alone does not grant permission to use, modify or redistribute the source.

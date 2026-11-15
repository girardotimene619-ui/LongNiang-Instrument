package com.longniang.instrument

import android.app.Activity
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowInsets
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView


/** Owns navigation, SAF imports and cancellation of pending chart loads. */
class MainActivity : Activity() {
    private lateinit var game: TapSurface
    private val ui by lazy { Frontend(this) }
    private lateinit var pages: android.widget.FrameLayout
    private enum class Page { HOME, PLAY, PRACTICE, SETTINGS, GAME }
    private var page = Page.HOME
    private var gameReturn = Page.PLAY
    private var category = "Expert"
    private var query = ""
    private var settingsCategory = 0
    private var menuMusic: MenuMusic? = null
    private val settings by lazy { PlayerSettings(this) }
    private var audio: PcmAudioPlayer? = null
    private var audioDialog: android.app.AlertDialog? = null
    private var pendingTimestampExport: java.io.File? = null
    private var pendingPlayExport: java.io.File? = null
    private var pendingPerformanceExport: java.io.File? = null
    private var performanceListener: android.view.Window.OnFrameMetricsAvailableListener? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        game = TapSurface(this)
        pendingPerformanceExport = state?.getString("performanceExport")?.let { name ->
            java.io.File(cacheDir, name).takeIf { it.name == name && name.startsWith("render-perf-") && it.isFile }
        }
        pendingTimestampExport = state?.getString("timestampExport")?.let { name ->
            game.timestampCapture?.fileNamed(name)
        }
        pendingPlayExport = state?.getString("playExport")?.let(game::playCaptureFile)
        game.setNoteSpeed(settings.noteSpeed.load())
        game.setHitVolume(settings.hitGain)
        game.setMusicVolume(settings.musicGain)
        val panel = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(12, 16, 36))
        }
        panel.addView(game, android.widget.FrameLayout.LayoutParams(-1, -1))
        debugStatus = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(0xB51A2039.toInt())
            visibility = android.view.View.GONE
        }
        panel.addView(debugStatus, android.widget.FrameLayout.LayoutParams(-2, -2,
            android.view.Gravity.TOP or android.view.Gravity.START).apply { setMargins(dp(8), dp(6), 0, 0) })
        game.onStatus = { message -> if (debugStatus.visibility == android.view.View.VISIBLE) debugStatus.text = message }
        val menu = ui.pauseButton { showGameMenu() }
        panel.addView(menu, android.widget.FrameLayout.LayoutParams(dp(48), dp(48),
            android.view.Gravity.TOP or android.view.Gravity.END).apply { setMargins(0, dp(8), dp(10), 0) })
        panel.setOnApplyWindowInsetsListener { _, insets ->
            // Cutout insets move the overlays. The track stays centered on the display.
            if (Build.VERSION.SDK_INT >= 28) {
                val cutout = insets.displayCutout
                fun place(view: android.view.View, left: Int, top: Int, right: Int) {
                    val params = view.layoutParams as android.widget.FrameLayout.LayoutParams
                    if (params.leftMargin != left || params.topMargin != top || params.rightMargin != right) {
                        params.setMargins(left, top, right, 0)
                        view.layoutParams = params
                    }
                }
                val top = cutout?.safeInsetTop ?: 0
                place(menu, 0, dp(8) + top, dp(10) + (cutout?.safeInsetRight ?: 0))
                place(debugStatus, dp(8) + (cutout?.safeInsetLeft ?: 0), dp(6) + top, 0)
            }
            insets
        }
        pages = android.widget.FrameLayout(this).apply { addView(panel, android.widget.FrameLayout.LayoutParams(-1, -1)) }
        setContentView(pages)
        showHome()
        game.loadStageImage(assets)
        panel.requestApplyInsets()
    }

    private fun showPage(next: Page, view: android.view.View? = null) {
        if (pages.childCount > 1) pages.removeViewAt(1)
        pages.getChildAt(0).visibility = if (next == Page.GAME) android.view.View.VISIBLE else android.view.View.INVISIBLE
        if (view != null) pages.addView(view, android.widget.FrameLayout.LayoutParams(-1, -1))
        page = next
        refreshMenuMusic()
    }

    private fun refreshMenuMusic() {
        if (activityResumed && menuMusic == null) {
            menuMusic = MenuMusic(this).apply {
                volume(settings.musicGain)
            }
        }
        menuMusic?.show(activityResumed && page != Page.GAME)
    }

    private fun showHome() = showPage(Page.HOME, ui.home(
        { openLibrary(Page.PLAY) }, { openLibrary(Page.PRACTICE) }, { showSettings(0) }))

    private fun openLibrary(next: Page) {
        if (page != next) { category = "Expert"; query = "" }
        showPage(next, ui.page(ui.label("正在读取曲库…", 22f)))
        showSongLibrary()
    }

    private fun returnFromGame() {
        if (game.isSegmentPractice() && segmentDraft != null) game.endPractice(retainPcm = true)
        else endCurrentPractice()
        showPage(gameReturn)
        showSongLibrary()
    }

    @Deprecated("Android back navigation")
    override fun onBackPressed() {
        when (page) {
            Page.GAME -> showGameMenu()
            Page.PLAY, Page.PRACTICE, Page.SETTINGS -> showHome()
            Page.HOME -> super.onBackPressed()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                it.hide(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    private lateinit var debugStatus: TextView
    private val debugBuild get() = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun showGameMenu() {
        if (page != Page.GAME) return
        game.clearMenuTouches()
        game.pauseForMenu()
        val actions = ui.column().apply { setPadding(dp(16), dp(8), dp(16), dp(16)) }
        val dialog = android.app.AlertDialog.Builder(this).setTitle("暂停").setView(actions).show()
        actions.addView(ui.button("重置") { dialog.dismiss(); game.restart() })
        actions.addView(ui.button("结束并返回曲库") { dialog.dismiss(); returnFromGame() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        ui.styleDialog(dialog)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnCancelListener { game.resumeFromMenu() }
    }

    private fun showDebugMenu() {
        val rows = mutableListOf<Pair<String, () -> Unit>>()
        val showDebug = debugStatus.visibility != android.view.View.VISIBLE
        rows += (if (showDebug) "显示调试信息" else "隐藏调试信息") to {
            debugStatus.visibility = if (showDebug) android.view.View.VISIBLE else android.view.View.GONE
        }
        rows += "音频诊断" to { showAudioTest() }
        if (debugBuild) {
            game.timestampCapture?.let { capture ->
                rows += "AudioTrack 时间戳采集" to { showTimestampCapture(capture) }
            }
            rows += "整局输入与判定记录" to { showPlayCapture() }
            game.upperTouchComparisonLabel()?.let { label ->
                rows += "触控上缘对照 · $label" to {
                    val enabled = game.toggleUpperTouchComparison()
                    toast("下次重置 / 开始应用${if (enabled) " +5 px 对照" else "基线"}")
                }
            }
            rows += "音效后端 · ${game.hitBackendDescription()}" to { showHitBackendTest() }
            rows += "音效类别 · ${game.hitGroup.label}" to { showHitSoundGroup() }
            rows += "保存冻结诊断（先不要重置）" to {
                game.captureFreeze()
                toast("正在保存冻结诊断")
            }
            rows += (if (game.showHitEffects) "诊断：关闭命中特效" else "诊断：开启命中特效") to {
                game.showHitEffects = !game.showHitEffects
            }
            rows += "切换 TEST / AUDIO 测试模式" to {
                audioDialog?.dismiss(); audio?.release(); audio = null
                game.switchMode()
            }
            rows += "加载 SUS 测试谱" to { toast(game.loadControlledSus()) }
            rows += "导入本地音频测试" to {
                startActivityForResult(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("audio/*"), 33)
            }
        }
        ui.showSettingsDialog(android.app.AlertDialog.Builder(this).setTitle("调试工具")
            .setItems(rows.map { it.first }.toTypedArray()) { _, index -> rows[index].second() }
            .setNegativeButton("收起", null)).setCanceledOnTouchOutside(false)
    }

    private fun showHitBackendTest() {
        val options = HitSoundBackend.entries
        ui.showSettingsDialog(android.app.AlertDialog.Builder(this).setTitle("音效后端\n当前：${game.hitBackendDescription()}")
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), game.actualHitBackend()?.ordinal ?: -1) { dialog, index ->
                dialog.dismiss()
                val backend = options[index]
                if (game.actualHitBackend() == backend) return@setSingleChoiceItems
                ui.showSettingsDialog(android.app.AlertDialog.Builder(this).setTitle("切换音效后端")
                    .setMessage("切换到 ${backend.label} 将停止旧音效，并从当前练习起点重试。重新进入曲目时默认使用 PCM Mixer。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("切换并重试") { _, _ ->
                        if (!game.switchHitBackend(backend)) toast("请先打开一轮练习")
                    })
            }.setNegativeButton("返回", null))
    }

    private fun showHitSoundGroup() {
        val options = HitSoundGroup.entries
        ui.showSettingsDialog(android.app.AlertDialog.Builder(this).setTitle("只播放选定的音效类别")
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), game.hitGroup.ordinal) { dialog, index ->
                game.hitGroup = options[index]
                dialog.dismiss()
            }.setNegativeButton("返回", null))
    }

    private fun showTimestampCapture(capture: AudioTimestampCapture) {
        val startOrStop = if (capture.isRecording) "停止并保存" else "开始采集"
        ui.showSettingsDialog(android.app.AlertDialog.Builder(this).setTitle("AudioTrack 时间戳")
            .setMessage(capture.status)
            .setNeutralButton(startOrStop) { _, _ ->
                if (capture.isRecording) capture.stop() else capture.start()
                toast(capture.status)
            }
            .setPositiveButton("保存并导出最近一份") { _, _ ->
                capture.stop()
                ui.showSettingsDialog(android.app.AlertDialog.Builder(this).setTitle("导出时间戳")
                    .setItems(arrayOf("完整采集 CSV", "首次跳变前后 CSV")) { _, choice ->
                        exportTimestampCapture(capture, choice == 1)
                    }
                    .setNegativeButton("取消", null))
            }
            .setNegativeButton("返回", null))
    }

    private fun startRenderPerformance() {
        val capture = game.startRenderPerformance()
        performanceListener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            capture.uiFrameNs = metrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION)
            capture.uiDeadlineNs = metrics.getMetric(android.view.FrameMetrics.INPUT_HANDLING_DURATION) +
                metrics.getMetric(android.view.FrameMetrics.ANIMATION_DURATION) +
                metrics.getMetric(android.view.FrameMetrics.LAYOUT_MEASURE_DURATION)
            capture.uiDroppedCallbacks += dropped
        }.also { window.addOnFrameMetricsAvailableListener(it, android.os.Handler(Looper.getMainLooper())) }
    }

    private fun stopRenderPerformance() {
        performanceListener?.let(window::removeOnFrameMetricsAvailableListener)
        performanceListener = null
        game.stopRenderPerformance()
    }

    private fun showRenderPerformance() {
        val running = game.isMeasuringRender
        ui.showSettingsDialog(android.app.AlertDialog.Builder(this).setTitle("游戏性能监测")
            .setMessage((if (running) "正在记录" else "未记录") +
                "。开启后进入游戏，结束并返回设置导出。CSV 保留最近 36,000 帧，提交节奏与实际显示帧率分别测量。")
            .setNeutralButton(if (running) "停止记录" else "开始记录") { _, _ ->
                if (running) stopRenderPerformance() else startRenderPerformance()
            }
            .setPositiveButton("停止并导出 CSV") { _, _ ->
                stopRenderPerformance()
                val report = game.latestRenderPerformance()
                if (report == null) toast("没有性能记录")
                else runLibraryWork {
                    val file = java.io.File.createTempFile("render-perf-", ".csv", cacheDir)
                    report.export(file)
                    if (Build.VERSION.SDK_INT >= 29) {
                        val resolver = contentResolver
                        val fields = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                        val uri = requireNotNull(resolver.insert(
                            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, fields))
                        try {
                            requireNotNull(resolver.openOutputStream(uri)).use { output ->
                                file.inputStream().use { it.copyTo(output) }
                            }
                            fields.clear()
                            fields.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                            check(resolver.update(uri, fields, null, null) == 1)
                        } catch (error: Exception) {
                            resolver.delete(uri, null, null)
                            throw error
                        } finally {
                            file.delete()
                        }
                        runOnUiThread { if (!isDestroyed) toast("已保存到下载目录：${file.name}") }
                        return@runLibraryWork
                    }
                    runOnUiThread {
                        if (!isDestroyed && !isFinishing) {
                            pendingPerformanceExport = file
                            startActivityForResult(android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT)
                                .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("text/csv")
                                .putExtra(android.content.Intent.EXTRA_TITLE, file.name), 37)
                        }
                    }
                }
            }
            .setNegativeButton("返回", null))
    }

    private fun showPlayCapture() {
        val active = game.isCapturingPlay()
        ui.showSettingsDialog(android.app.AlertDialog.Builder(this).setTitle("整局输入与判定记录")
            .setMessage("${game.playCaptureStatus()}\n建议先开始记录，再从游戏菜单重置开曲。")
            .setNeutralButton(if (active) "停止并保存" else "开始记录") { _, _ ->
                if (active) game.stopPlayCapture() else game.startPlayCapture()
                toast(game.playCaptureStatus())
            }
            .setPositiveButton("保存并导出 CSV") { _, _ ->
                game.exportPlayCapture { file ->
                    runOnUiThread {
                        if (isDestroyed || isFinishing) return@runOnUiThread
                        if (file == null) toast("没有可导出的记录 · ${game.playCaptureStatus()}")
                        else {
                            pendingPlayExport = file
                            startActivityForResult(android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT)
                                .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("text/csv")
                                .putExtra(android.content.Intent.EXTRA_TITLE, file.name), 36)
                        }
                    }
                }
            }.setNegativeButton("返回", null))
    }

    private fun exportTimestampCapture(capture: AudioTimestampCapture, incidentOnly: Boolean) {
        capture.latest(incidentOnly) { file ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (file == null) {
                    toast(if (incidentOnly) "最近一轮没有首个跳变记录" else "没有可导出的时间戳记录")
                } else {
                    pendingTimestampExport = file
                    startActivityForResult(android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(android.content.Intent.CATEGORY_OPENABLE)
                        .setType("text/csv")
                        .putExtra(android.content.Intent.EXTRA_TITLE, file.name), 35)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingTimestampExport?.let { outState.putString("timestampExport", it.name) }
        pendingPlayExport?.let { outState.putString("playExport", it.name) }
        pendingPerformanceExport?.let { outState.putString("performanceExport", it.name) }
        super.onSaveInstanceState(outState)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun showNoteSpeed() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
        }
        val value = TextView(this).apply {
            textSize = 22f
            gravity = android.view.Gravity.CENTER
            text = game.noteSpeed.label
        }
        content.addView(value)
        val presetButtons = mutableListOf<Pair<NoteSpeed, TextView>>()
        fun updatePresets(selected: NoteSpeed) {
            for ((speed, button) in presetButtons) {
                button.isSelected = speed == selected
                button.background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x38D9F8FF), ui.glass(button.isSelected), null)
            }
        }
        val slider = SeekBar(this).apply {
            max = NoteSpeed.MAX - NoteSpeed.MIN
            progress = game.noteSpeed.tenths - NoteSpeed.MIN
            contentDescription = "谱面流速，范围 1.0 到 20.0"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    val selected = NoteSpeed(NoteSpeed.MIN + progress)
                    value.text = selected.label
                    updatePresets(selected)
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
        content.addView(slider, LinearLayout.LayoutParams(-1, dp(48)))
        for (row in NoteSpeed.PRESETS.chunked(4)) {
            val presets = ui.row()
            for (speed in row) {
                val button = ui.button(speed.label) { slider.progress = speed.tenths - NoteSpeed.MIN }.apply {
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, 0, 0, 0)
                }
                presetButtons += speed to button
                presets.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                })
            }
            content.addView(presets)
        }
        updatePresets(game.noteSpeed)
        ui.showSettingsDialog(android.app.AlertDialog.Builder(this).setTitle("谱面流速 · Note Speed")
            .setView(android.widget.ScrollView(this).apply { addView(content) })
            .setNegativeButton("取消", null)
            .setPositiveButton("应用") { _, _ ->
                val selected = NoteSpeed(NoteSpeed.MIN + slider.progress)
                settings.noteSpeed.save(selected)
                game.setNoteSpeed(selected)
                if (page == Page.SETTINGS) showSettings(settingsCategory)
            })
    }
    private fun showAudioTest() {
        if (audioDialog?.isShowing == true) return
        val gameAudio = game.audioMode
        val player = if (gameAudio) game.audioPlayer() else
            audio?.takeUnless { it.isClosed } ?: PcmAudioPlayer().also { audio = it }
        val builder = android.app.AlertDialog.Builder(this)
            .setTitle(if (gameAudio) "练习音频诊断" else "独立 PCM 测试")
            .setMessage(player.diagnostics)
            .setNegativeButton("关闭", null)
        if (!gameAudio) builder.setPositiveButton("播放", null).setNeutralButton("停止并释放", null)
        val dialog = builder.create()
        audioDialog = dialog
        val handler = Handler(Looper.getMainLooper())

        val refresh = object : Runnable {
            override fun run() {
                if (dialog.isShowing) {
                    dialog.setMessage(player.diagnostics + "\n" + game.diagnosticText() + "\nUI读取ns=${System.nanoTime()}")
                    if (!gameAudio) dialog.getButton(-1).isEnabled = !player.isClosed && !player.pending && !player.isPlaying
                    handler.postDelayed(this, 250)
                }
            }
        }
        dialog.setOnShowListener {
            if (!gameAudio) {
                dialog.getButton(-1).setOnClickListener {
                    if (!player.pending && !player.isPlaying) player.play()
                }
                dialog.getButton(-3).setOnClickListener { player.stop() }
            }
            handler.post(refresh)
        }
        dialog.setOnDismissListener {
            handler.removeCallbacks(refresh)
            if (!gameAudio) {
                player.release()
                if (audio === player) audio = null
            }
            audioDialog = null
        }
        dialog.show()
        ui.styleSettingsDialog(dialog)
    }
    private val songs by lazy { SongRepository(java.io.File(filesDir, "m34")) }
    private var libraryWorker: Thread? = null
    private var modeDialog: android.app.AlertDialog? = null
    private data class SegmentDraft(val title: String, val source: PcmSource, val chart: MixedChart,
                                    val pcm: java.io.File, val chartId: String, var startPoint: Int? = null, var endPoint: Int? = null)
    private var segmentDraft: SegmentDraft? = null
    private var openLibraryWhenIdle = false
    private var activityResumed = false

    private fun runLibraryWork(work: () -> Unit) {
        if (libraryWorker?.isAlive == true) {
            toast("正在处理曲目资源")
            return
        }
        libraryWorker = Thread({
            try { work() }
            catch (error: Exception) { runOnUiThread {
                if (!isDestroyed && !isFinishing) toast("曲目资源处理失败：${error.message}")
            } }
            finally {
                val finishedWorker = Thread.currentThread()
                runOnUiThread {
                    if (libraryWorker !== finishedWorker) return@runOnUiThread
                    libraryWorker = null
                    if (openLibraryWhenIdle) {
                        openLibraryWhenIdle = false
                        showSongLibrary()
                    }
                }
            }
        }, "SongLibrary").also { it.start() }
    }

    private fun toast(message: String) =
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()

    private fun showSongLibrary() {
        if (!activityResumed || isDestroyed || isFinishing) return
        if (libraryWorker?.isAlive == true) {
            openLibraryWhenIdle = true
            return
        }
        runLibraryWork {
            val packs = songs.songs().flatMap { root ->
                ResourceMatcher.difficulties(root).map { root to it }
            }
            runOnUiThread {
                if (!activityResumed || isDestroyed || isFinishing) return@runOnUiThread
                if (page == Page.PLAY || page == Page.PRACTICE) showLibraryPage(packs)
            }
        }
    }

    private fun chooseSongAudio(root: java.io.File, item: SongDifficulty) {
        val problem = item.problem
        if (problem != null) {
            android.app.AlertDialog.Builder(this).setTitle(item.title).setMessage(problem)
                .setPositiveButton("关闭", null).show()
            return
        }
        if (item.audio.size == 1) playSong(item, item.audio.single())
        else android.app.AlertDialog.Builder(this).setTitle("选择音频")
            .setItems(item.audio.map { it.relativeTo(root).invariantSeparatorsPath }.toTypedArray()) { _, i ->
                playSong(item, item.audio[i])
            }.setNegativeButton("取消", null).show()
    }

    private fun showLibraryPage(packs: List<Pair<java.io.File, SongDifficulty>>) {
        val target = page
        val body = ui.column()
        val header = ui.row().apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        header.addView(ui.button("‹ 返回") { showHome() })
        header.addView(ui.label(if (target == Page.PLAY) "游玩曲库" else "练习曲库", 23f))
        val search = android.widget.EditText(this).apply {
            hint = "搜索曲名 / 别名"
            isSingleLine = true
            setTextColor(Color.WHITE)
            setHintTextColor(0xFFD1E4EE.toInt())
            setText(query)
        }
        header.addView(search, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(ui.button("清空") { search.setText("") })
        val draft = segmentDraft
        if (target == Page.PRACTICE && draft != null)
            header.addView(ui.button("返回片段编辑") { showSegmentEditor(draft) })
        header.addView(ui.button("＋ 导入") {
            startActivityForResult(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*"), 34)
        })
        body.addView(header)
        val content = ui.row()
        val rail = ui.column()
        val list = android.widget.ListView(this).apply {
            background = ui.glass()
            dividerHeight = ui.dp(5)
        }
        content.addView(rail, LinearLayout.LayoutParams(ui.dp(116), -1))
        content.addView(list, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = ui.dp(10) })
        body.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        fun filter() {
            val filtered = packs.filter { LibraryFilter.matches(it.second, category, query) }
            list.adapter = object : android.widget.BaseAdapter() {
                override fun getCount() = maxOf(1, filtered.size)
                override fun getItem(position: Int) = filtered.getOrNull(position)
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, recycled: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                    val song = getItem(position)?.second ?: return ui.label("没有匹配的谱面。可切换难度、清空搜索或导入 ZIP。").apply {
                        setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(18))
                    }
                    return ui.row().apply {
                        setPadding(ui.dp(8), ui.dp(5), ui.dp(8), ui.dp(5))
                        addView(android.widget.ImageView(this@MainActivity).apply {
                            setImageBitmap(ui.cover(song.covers.firstOrNull()))
                            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        }, LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)))
                        addView(ui.column().apply {
                            setPadding(ui.dp(12), 0, 0, 0)
                            addView(ui.label(song.title, 19f))
                            addView(ui.label(song.label + song.problem?.let { " · $it" }.orEmpty(), 14f))
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                    }
                }
            }
            list.setOnItemClickListener { _, _, i, _ -> filtered.getOrNull(i)?.let { chooseSongAudio(it.first, it.second) } }
        }
        fun categories() {
            rail.removeAllViews()
            for (item in LibraryFilter.categories) rail.addView(ui.button(item, item == category) {
                category = item; categories(); filter()
            }.apply { textSize = 18f; isSingleLine = true }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s.toString(); filter() }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        categories()
        filter()
        body.isFocusableInTouchMode = true
        body.requestFocus()
        showPage(target, ui.page(body))
    }

    private fun showSettings(index: Int) {
        settingsCategory = index
        val body = ui.column()
        val header = ui.row().apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        header.addView(ui.button("‹ 返回") { showHome() })
        header.addView(ui.label("设置", 25f))
        body.addView(header)
        val content = ui.row()
        val rail = ui.column()
        listOf("通用", "音频", "调试", "关于").forEachIndexed { i, title ->
            rail.addView(ui.button(title, i == index) { showSettings(i) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        content.addView(rail, LinearLayout.LayoutParams(ui.dp(116), -1))
        val options = ui.column().apply { setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10)) }
        val scroll = android.widget.ScrollView(this).apply { addView(options); background = ui.glass() }
        content.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = ui.dp(10) })
        body.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        when (index) {
            0 -> options.addView(ui.button("音符流速 · ${game.noteSpeed.label}") { showNoteSpeed() })
            1 -> {
                val prefs = settings.preferences
                fun slider(title: String, key: String, limit: Int, default: Int, update: (Float) -> Unit) {
                    val current = prefs.getInt(key, default).coerceIn(0, limit)
                    val label = ui.label("$title · $current%")
                    options.addView(label)
                    options.addView(SeekBar(this).apply {
                        max = limit; progress = current
                        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                                label.text = "$title · $progress%"
                                prefs.edit().putInt(key, progress).apply()
                                update(progress / 100f)
                            }
                            override fun onStartTrackingTouch(bar: SeekBar) = Unit
                            override fun onStopTrackingTouch(bar: SeekBar) = Unit
                        })
                    })
                }
                slider("谱面与菜单 BGM", "music_volume", 100, 100) {
                    game.setMusicVolume(it); menuMusic?.volume(it)
                }
                slider("打击音效", "hits_volume", MAX_HIT_VOLUME_PERCENT, 65) {
                    if (!prefs.getBoolean("hits_muted", false)) game.setHitVolume(it)
                }
                options.addView(android.widget.CheckBox(this).apply {
                    text = "静音打击音效"
                    setTextColor(Color.WHITE)
                    isChecked = prefs.getBoolean("hits_muted", false)
                    setOnCheckedChangeListener { _, checked ->
                        prefs.edit().putBoolean("hits_muted", checked).apply()
                        game.setHitVolume(if (checked) 0f else prefs.getInt("hits_volume", 65) / 100f)
                    }
                })
            }
            2 -> {
                options.addView(ui.label("记录与导出"))
                options.addView(ui.button("游戏性能监测与导出") { showRenderPerformance() })
                options.addView(ui.button("整局输入、触控轨迹与判定 CSV") { showPlayCapture() })
                game.timestampCapture?.let { capture ->
                    options.addView(ui.button("AudioTrack 时间戳与导出") { showTimestampCapture(capture) })
                }
                options.addView(ui.button("音频诊断 / 独立 PCM 测试") { showAudioTest() })
                options.addView(ui.label("实验性工具"))
                options.addView(ui.button("判定对照、触控及音效工具") { showDebugMenu() })
            }
            else -> {
                val version = packageManager.getPackageInfo(packageName, 0).versionName
                options.addView(ui.label("龙娘の乐器", 26f))
                options.addView(ui.label("版本 $version · Android 节奏练习"))
                options.addView(ui.label("本地 SUS 谱面 · Canvas 与 AudioTrack"))
                options.addView(ui.label("项目内置旧判定内核与 PoC 判定对照。"))
            }
        }
        showPage(Page.SETTINGS, ui.page(body))
    }

    private val chartLoad = ChartLoad()
    private var chartLoadDialog: android.app.AlertDialog? = null
    private var chartLoadWorker: Thread? = null

    private fun cancelChartLoad() {
        chartLoad.cancel()
        chartLoadDialog?.dismiss()
        chartLoadDialog = null
        chartLoadWorker?.interrupt()
        chartLoadWorker = null
    }

    private fun <T> loadChart(title: String, work: ((String, Int) -> Unit) -> T,
                              discard: (T) -> Unit = {}, ready: (T) -> Unit) {
        val id = chartLoad.begin() ?: return
        val panel = ui.column().apply { setPadding(dp(24), dp(8), dp(24), dp(12)) }
        panel.addView(ui.label(title, 16f))
        val stage = ui.label("正在读取资源", 14f)
        val progress = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        panel.addView(stage)
        panel.addView(progress, LinearLayout.LayoutParams(-1, dp(20)))
        val dialog = android.app.AlertDialog.Builder(this).setTitle("正在加载谱面").setView(panel)
            .setNegativeButton("取消") { _, _ -> cancelChartLoad() }.create()
        dialog.setOnCancelListener { cancelChartLoad() }
        chartLoadDialog = dialog
        Handler(Looper.getMainLooper()).postDelayed({
            if (chartLoad.owns(id) && activityResumed && !isFinishing && !isDestroyed) {
                dialog.show()
                ui.styleDialog(dialog)
                dialog.window?.setLayout(minOf(dp(410), (resources.displayMetrics.widthPixels * .82f).toInt()), -2)
                dialog.setCanceledOnTouchOutside(false)
            }
        }, 120)
        val returnPage = page
        chartLoadWorker = Thread({
            val result = runCatching {
                work { message, completed ->
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    runOnUiThread {
                        if (chartLoad.owns(id)) {
                            stage.text = message
                            progress.progress = completed
                        }
                    }
                }
            }
            runOnUiThread {
                val valid = chartLoad.finish(id)
                if (!valid) result.onSuccess(discard)
                else {
                    chartLoadDialog = null
                    chartLoadWorker = null
                    dialog.dismiss()
                    if (!activityResumed || isDestroyed || isFinishing || page != returnPage) result.onSuccess(discard)
                    else result.fold(ready) { toast("谱面加载失败：${it.message ?: "未知错误"}") }
                }
            }
        }, "ChartPreparation").apply { start() }
    }

    private data class LoadedSong(val draft: SegmentDraft, val playback: MixedPlayback?)

    private fun playSong(item: SongDifficulty, audioFile: java.io.File) {
        val selectionPage = page
        val title = "${item.title} · ${item.label}"
        loadChart(title, work = { stage ->
            val bytes = item.chart.readBytes()
            stage("正在解析 SUS 谱面", 10)
            val parsed = com.longniang.instrument.sus.NoteResolver.resolve(bytes)
            stage("正在建立谱面判定计划", 30)
            val provenance = assets.open("combo-provenance.tsv").bufferedReader().use { it.readLines() }
            val chart = MixedChart.prepare(parsed, comboProvenance = provenance)
            require(chart.playable) { chart.diagnostics.firstOrNull { it.error }?.message ?: "谱面不兼容" }
            stage("正在解码音频", 55)
            val pcm = java.io.File.createTempFile("m34-output-", ".pcm", cacheDir)
            var transferred = false
            try {
                val frames = LocalAudioDecoder.decode(audioFile, pcm)
                val duration = SampleTime.us(frames, PcmSignal.sampleRate)
                require(chart.lastTimeUs < duration) { "音频未覆盖谱面结尾" }
                stage(if (selectionPage == Page.PRACTICE) "正在准备谱面预览" else "正在准备游玩会话", 85)
                val draft = SegmentDraft(title, FilePcmSource(pcm, frames), chart, pcm, parsed.id)
                val playback = if (selectionPage == Page.PRACTICE) null else
                    MixedSession(chart, 0, duration, openingTapEarlyUs = chart.openingTapEarlyUs())
                LoadedSong(draft, playback).also { transferred = true }
            } finally {
                if (!transferred) pcm.delete()
            }
        }, discard = { it.draft.pcm.delete() }) { loaded ->
            val draft = loaded.draft
            gameReturn = selectionPage
            segmentDraft?.let(::releaseSegmentDraft)
            segmentDraft = null
            if (loaded.playback == null) {
                game.endPractice()
                showSegmentEditor(draft)
            } else {
                game.loadMixedAudio(draft.source, draft.chart, PracticeMode.FULL, draft.pcm,
                    debugBuild, draft.chartId, title = draft.title, prepared = loaded.playback)
                showPage(Page.GAME)
                game.restart()
            }
        }
    }

    private var decodeRequest = 0

    private fun showSegmentEditor(draft: SegmentDraft) {
        segmentDraft = draft
        val max = (draft.source.totalFrames / (PcmSignal.sampleRate / 100)).toInt()
        val content = ui.column().apply { setPadding(dp(16), 0, dp(16), 0) }
        val buttons = ui.row()
        val label = ui.label("", 15f)
        val slider = object : SeekBar(this) {
            private val rangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAA9DCBFF.toInt() }
            override fun onDraw(canvas: Canvas) {
                if (max > 0) {
                    val usable = (width - paddingLeft - paddingRight).coerceAtLeast(0)
                    fun x(point: Int) = paddingLeft + usable * (point.toFloat() / max)
                    val a = draft.startPoint?.let(::x)
                    val b = draft.endPoint?.let(::x)
                    val y = height * .75f
                    if (a != null && b != null) canvas.drawRect(minOf(a, b), y - dp(1), maxOf(a, b), y + dp(1), rangePaint)
                    if (a != null) canvas.drawCircle(a, y, dp(2).toFloat(), rangePaint)
                    if (b != null) canvas.drawCircle(b, y, dp(2).toFloat(), rangePaint)
                }
                super.onDraw(canvas)
            }
        }.apply { this.max = max }
        val preview = SegmentPreview(this, draft.chart, max * 10_000L, game.noteSpeed,
            game.trackStyle, game.stageImageSettings, game.stageImage)
        val previewHeight = (resources.displayMetrics.heightPixels * .42f).toInt()
        var previewTime = draft.startPoint ?: draft.endPoint ?: 0
        var editingStart: Boolean? = null
        var confirmed = false
        lateinit var dialog: android.app.AlertDialog
        lateinit var minus: TextView
        lateinit var plus: TextView
        fun time(cs: Int) = "%d.%02d".format(java.util.Locale.ROOT, cs / 100, cs % 100)
        fun selection(): PracticeSegment? {
            val first = draft.startPoint ?: return null
            val second = draft.endPoint ?: return null
            return PracticeSegment(minOf(first, second), maxOf(first, second))
        }
        fun editedPoint() = when (editingStart) {
            true -> draft.startPoint
            false -> draft.endPoint
            null -> null
        }
        fun refresh() {
            slider.progress = previewTime
            slider.invalidate()
            preview.at = previewTime * 10_000L
            val ab = selection()
            val range = when {
                ab != null -> "A ${time(ab.a)} s · B ${time(ab.b)} s · 长度 ${time(ab.b - ab.a)} s"
                draft.startPoint != null -> "起点 ${time(requireNotNull(draft.startPoint))} s · 未设置终点"
                draft.endPoint != null -> "终点 ${time(requireNotNull(draft.endPoint))} s · 未设置起点"
                else -> "尚未设置起点和终点"
            }
            val hint = when (editingStart) {
                true -> " · 设置起点"
                false -> " · 设置终点"
                null -> ""
            }
            val warning = when {
                ab == null -> ""
                ab.a == ab.b -> "\n两个点不能重合"
                else -> listOfNotNull(
                    "A 切入的长音不参与判定".takeIf { ab.cutsStart(draft.chart) },
                    "B 切断的长音不参与判定".takeIf { ab.cutsEnd(draft.chart) },
                ).joinToString(" · ", prefix = "\n").trimEnd()
            }
            label.text = "预览 ${time(previewTime)} s$hint\n$range$warning"
            val canAdjust = editedPoint() != null
            for (control in listOf(minus, plus)) {
                control.isEnabled = canAdjust
                control.alpha = if (canAdjust) 1f else .45f
            }
            dialog.getButton(-1).isEnabled = ab != null && ab.a < ab.b
        }
        fun commitPoint() {
            when (editingStart) {
                true -> draft.startPoint = previewTime
                false -> draft.endPoint = previewTime
                null -> return
            }
            refresh()
        }
        fun adjust(delta: Int) {
            val point = editedPoint() ?: return
            previewTime = (point + delta).coerceIn(0, max)
            commitPoint()
        }
        fun button(text: String, action: () -> Unit): TextView {
            val view = ui.button(text, action = action).apply {
                gravity = android.view.Gravity.CENTER
                setPadding(dp(3), 0, dp(3), 0)
                textSize = 14f
            }
            buttons.addView(view, LinearLayout.LayoutParams(0, -2, 1f))
            return view
        }
        button("起点") { editingStart = true; refresh() }
        button("终点") { editingStart = false; refresh() }
        minus = button("−0.10 s") { adjust(-10) }
        plus = button("+0.10 s") { adjust(10) }
        label.setOnClickListener { commitPoint() }
        slider.contentDescription = "预览时间，步长 0.01 秒"
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            private var dragging = false
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                previewTime = progress
                if (dragging) refresh() else if (editingStart != null) commitPoint() else refresh()
            }
            override fun onStartTrackingTouch(bar: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(bar: SeekBar) {
                dragging = false
                commitPoint()
            }
        })
        content.addView(buttons)
        content.addView(label)
        content.addView(slider)
        val previewFrame = android.widget.FrameLayout(this).apply {
            background = ui.glass()
            clipToOutline = true
            setPadding(dp(1), dp(1), dp(1), dp(1))
            addView(preview, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        content.addView(previewFrame, LinearLayout.LayoutParams(-1, previewHeight).apply { topMargin = dp(5) })
        val scroll = android.widget.ScrollView(this).apply { addView(content); background = ui.glass() }
        dialog = android.app.AlertDialog.Builder(this).setTitle(draft.title).setView(scroll)
            .setNegativeButton("取消", null).setPositiveButton("确认片段", null).show()
        dialog.getButton(-1).setOnClickListener {
            val ab = selection()?.takeIf { it.a < it.b } ?: return@setOnClickListener
            loadChart(draft.title, work = { stage ->
                stage("正在准备片段判定会话", 55)
                val chart = ab.chartForPlay(draft.chart)
                Triple(SegmentPcmSource(draft.source, ab), chart, MixedSession(chart, ab.startUs, ab.endUs))
            }) { (source, chart, playback) ->
                if (dialog.isShowing && segmentDraft === draft) {
                    confirmed = true
                    dialog.dismiss()
                    game.loadMixedAudio(source, chart, PracticeMode.FULL, draft.pcm, false,
                        draft.chartId, ab, draft.title, prepared = playback)
                    showPage(Page.GAME)
                }
            }
        }
        ui.styleDialog(dialog)
        modeDialog = dialog
        dialog.setOnDismissListener {
            if (modeDialog === dialog) modeDialog = null
            if (!confirmed) {
                releaseSegmentDraft(draft)
                if (segmentDraft === draft) segmentDraft = null
                if (activityResumed && page == Page.PRACTICE) showSongLibrary()
            }
        }
        refresh()
    }

    private fun releaseSegmentDraft(draft: SegmentDraft) {
        // The segment player may still be releasing after returning to the editor.
        game.deleteSegmentPcm(draft.pcm)
    }

    private fun endCurrentPractice() {
        cancelChartLoad()
        decodeRequest++
        modeDialog?.dismiss()
        audioDialog?.dismiss()
        audio?.release(); audio = null
        val activeSegment = game.isSegmentPractice()
        game.endPractice()
        if (!activeSegment) segmentDraft?.let(::releaseSegmentDraft)
        segmentDraft = null
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 37) {
            val file = pendingPerformanceExport.also { pendingPerformanceExport = null } ?: return
            val uri = data?.data
            if (resultCode != RESULT_OK || uri == null) {
                file.delete()
                return
            }
            Thread({
                val error = runCatching {
                    requireNotNull(applicationContext.contentResolver.openOutputStream(uri, "wt")).use { output ->
                        file.inputStream().use { it.copyTo(output) }
                    }
                }.exceptionOrNull()
                if (error == null) file.delete()
                runOnUiThread {
                    if (!isDestroyed) toast(if (error == null) "已导出 ${file.name}" else "性能 CSV 导出失败：${error.message}")
                }
            }, "RenderPerformanceExport").start()
            return
        }
        if (requestCode == 35 || requestCode == 36) {
            val file = if (requestCode == 35) pendingTimestampExport.also { pendingTimestampExport = null }
                else pendingPlayExport.also { pendingPlayExport = null }
            if (resultCode == RESULT_OK && file != null && data?.data != null) {
                val uri = data.data!!
                runLibraryWork {
                    requireNotNull(contentResolver.openOutputStream(uri, "wt")) { "无法打开导出位置" }.use { output ->
                        file.inputStream().use { it.copyTo(output) }
                    }
                    runOnUiThread { if (!isDestroyed) toast("已导出 ${file.name}") }
                }
            }
            return
        }
        if (resultCode != RESULT_OK) return
        if (requestCode == 34) {
            val tag = "LongNiangZip"
            val attempt = android.os.SystemClock.elapsedRealtimeNanos()
            val uri = data?.data
            android.util.Log.i(tag, "attempt=$attempt picker-result uriPresent=${uri != null}")
            if (uri == null) {
                toast("文件选择器未返回 ZIP")
                return
            }
            runLibraryWork {
                var phase = "open"
                fun stage(value: String) {
                    if (!value.startsWith("cleanup")) phase = value
                    android.util.Log.i(tag, "attempt=$attempt phase=$value")
                }
                try {
                    stage("open")
                    requireNotNull(contentResolver.openInputStream(uri)) { "无法打开 ZIP" }
                        .use { input -> songs.importZip(input, ::stage) }
                    stage("post-result")
                    runOnUiThread {
                        if (!isDestroyed && !isFinishing) {
                            android.util.Log.i(tag, "attempt=$attempt phase=show-result")
                            toast("曲目已保存")
                            if (page == Page.PLAY || page == Page.PRACTICE) {
                                openLibraryWhenIdle = true
                            }
                        }
                    }
                } catch (error: Exception) {
                    android.util.Log.e(tag, "attempt=$attempt failed phase=$phase", error)
                    throw error
                }
            }
            return
        }
        if (requestCode != 33) return
        val uri = data?.data ?: return
        val selectionPage = page
        val request = ++decodeRequest
        fun report(message: String) =
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
        report("正在解码本地音频")
        Thread({
            var output: java.io.File? = null
            var published = false
            try {
                val input = java.io.File.createTempFile("m33-input-", ".audio", cacheDir)
                try {
                    requireNotNull(contentResolver.openInputStream(uri)).use { stream ->
                        input.outputStream().use { sink ->
                            val block = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val size = stream.read(block)
                                if (size < 0) break
                                total += size
                                require(total <= 128L * 1024 * 1024) { "音频文件超过 128 MiB" }
                                sink.write(block, 0, size)
                            }
                        }
                    }
                    val pcm = java.io.File.createTempFile("m33-output-", ".pcm", cacheDir)
                    output = pcm
                    val frames = LocalAudioDecoder.decode(input, pcm)
                    require(frames >= PcmLoop.practice.endFrame) { "固定 [2s,6s) 需要至少 6 秒音频" }
                    runOnUiThread {
                        if (isDestroyed || request != decodeRequest || page != selectionPage) pcm.delete()
                        else {
                            game.loadLocalAudio(FilePcmSource(pcm, frames), pcm)
                            gameReturn = Page.PLAY
                            showPage(Page.GAME)
                            report("本地 PCM 已就绪 · 点击开始")
                        }
                    }
                    published = true
                } finally { input.delete() }
            } catch (error: Exception) {
                runOnUiThread { if (!isDestroyed && request == decodeRequest) report("解码失败：${error.message}") }
            } finally { if (!published) output?.delete() }
        }, "LocalAudioDecode").start()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        refreshMenuMusic()
        if (page == Page.GAME) returnFromGame()
    }
    override fun onPause() {
        activityResumed = false
        refreshMenuMusic()
        openLibraryWhenIdle = false
        endCurrentPractice()
        game.stopPlayCapture()
        super.onPause()
    }
    override fun onDestroy() {
        openLibraryWhenIdle = false
        endCurrentPractice()
        game.stopPlayCapture()
        libraryWorker?.interrupt()
        menuMusic?.close()
        stopRenderPerformance()
        game.releaseHitSounds()
        game.closeFreezeWatch()
        game.timestampCapture?.close()
        super.onDestroy()
    }
}

/** Coordinates input, audio and session updates; the render thread consumes visual snapshots. */
class TapSurface(context: android.content.Context) : SurfaceView(context), SurfaceHolder.Callback {
    private val sessionLock = Any()
    @Volatile private var performanceCapture: RenderPerformanceCapture? = null
    private var stoppedPerformance: RenderPerformanceCapture? = null
    @Volatile private var performanceSessionId = 0
    private var performanceNextGcMs = 0L
    @Volatile private var performanceUpdateNs = -1L
    private var performanceSnapshotNs = 0L
    private var performanceStageNs = 0L
    private var performanceFeedbackNs = 0L
    private var performanceGeneration = 0L
    private var performanceRevision = 0L

    internal val isMeasuringRender get() = performanceCapture != null
    internal fun startRenderPerformance() = RenderPerformanceCapture().also {
        stoppedPerformance = null
        performanceNextGcMs = 0
        performanceSessionId = 0
        performanceUpdateNs = -1
        performanceCapture = it
    }
    internal fun stopRenderPerformance() {
        performanceCapture?.let {
            performanceCapture = null
            it.stop()
            stoppedPerformance = it
        }
    }
    internal fun latestRenderPerformance() = stoppedPerformance

    private fun notePerformanceSession(title: String?, chartId: String, mode: PracticeMode) {
        val capture = performanceCapture ?: return
        val displayMode = display?.mode
        performanceSessionId = capture.session("title=${title.orEmpty().replace(Regex("[\\r\\n]"), " ")}; chart=$chartId; mode=${mode.name}; " +
            "display_mode_id=${displayMode?.modeId}; display_mode_hz=${displayMode?.refreshRate}; " +
            "display_reported_hz=${display?.refreshRate}; note_speed=${noteSpeed.label}; " +
            "input_csv=${playCapture?.isRecording == true}; timestamp_csv=${timestampCapture?.isRecording == true}; " +
            "hit_effects=$showHitEffects; stage=${stageImageSettings.mode}; segment=$practiceSegment")
    }
    private var session = TapSession(MockChart.notes)
    private var mixed: MixedPlayback? = null
    private var comparisonChart: MixedChart? = null
    private var playfield = Playfield(0f, 0f, PlayfieldConfig.STAGE)
    private var requestedUpperTouchComparison = false
    private val clock = TestClock()
    var audioMode = false; private set
    @Volatile private var player: PcmAudioPlayer? = null
    private var observedToken: ClockToken? = null
    private var renderCount = 0L
    private var previousRenderNs = 0L
    private var renderIntervalNs = 0L
    private var lastRenderChartUs = 0L
    private var pcmSource: PcmSource = TestPcmSource
    private var ownedPcm: java.io.File? = null
    private var practiceLoop: PcmLoop? = PcmLoop.practice
    private var practiceSegment: PracticeSegment? = null
    private var practiceTitle: String? = null
    private var openingVisualLeadUs = 0L
    private var openingTapEarlyUs = 0L
    private var retainedSegmentPlayer: Pair<java.io.File, PcmAudioPlayer>? = null
    private val hasPractice get() = mixed != null || session.notes.isNotEmpty()
    internal val timestampCapture = if (context.applicationInfo.flags and
        android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
        AudioTimestampCapture(java.io.File(context.filesDir, "audio-timestamps")) else null
    internal fun audioPlayer(): PcmAudioPlayer = synchronized(sessionLock) {
        player?.takeUnless { it.isClosed } ?: PcmAudioPlayer(gameMode = true, source = pcmSource,
            offsetUs = (pcmSource as? OpeningPcmSource)?.offsetUs ?: 0L,
            loop = practiceLoop, timestampCapture = timestampCapture, initialVolume = musicVolume).also { player = it }
    }
    private var musicVolume = 1f
    internal fun setMusicVolume(volume: Float) = synchronized(sessionLock) {
        musicVolume = volume
        player?.setMusicVolume(volume)
    }
    @Volatile private var audioRunGeneration = 0L
    private fun activeClock(reading: AudioClockReading? = player?.reading): GameClock {
        if (!audioMode) return clock
        val current = reading?.takeIf { it.anchor.token.generation == audioRunGeneration }
            ?: AudioClockReading.idle(session.generation)
        return practiceSegment?.let { SegmentGameClock(current, it) }
            ?: practiceLoop?.let { LoopGameClock(current, it) } ?: current
    }
    private fun currentSnapshot(nowNs: Long): ClockSnapshot {
        val source = activeClock()
        val s = source.snapshot(maxOf(nowNs, System.nanoTime()))
        synchronizeLoop(s)
        return s
    }
    private fun synchronizeLoop(s: ClockSnapshot) {
        if (!audioMode || s.token.generation <= session.generation) return
        captureBoundary("LOOP", s)
        session.reset(s.token.generation)
        mixed?.reset()
        pointers.isolate(); resetFeedback()
    }
    private fun synchronizeInputEpoch(s: ClockSnapshot) {
        captureClock(s)
        if (s.token != observedToken || !s.canJudge) {
            val at = s.chartTimeUs.takeIf { s.valid && s.token.generation == session.generation }
            if (s.token != observedToken || lastCaptureCanJudge == true) {
                playCapture?.takeIf { it.isRecording }?.row("EPOCH_CANCEL", s.token.generation,
                    s.token.revision, at, s.monotonicNs, null, null, s.state, s.reason)
            }
            cancelInputs(at)
            observedToken = s.token
        }
        lastCaptureCanJudge = s.canJudge
    }
    private fun cancelInputs(at: Long? = null) {
        pendingMoves.clear()
        handler.removeCallbacks(flushMoves)
        session.cancel()
        mixed?.cancel(at)
        pointers.isolate()
        sustainFeedback.clear(submitSustain)
    }
    internal fun clearMenuTouches() = synchronized(sessionLock) {
        captureBoundary("MENU_CANCEL", updateSnapshot)
        cancelInputs()
    }

    internal fun comparisonEngine(): Boolean? = synchronized(sessionLock) {
        if (comparisonChart == null || mixed == null) null else mixed is PocPlayback
    }

    internal fun upperTouchComparisonLabel(): String? = synchronized(sessionLock) {
        if (mixed == null || playfield.columns != 12) return@synchronized null
        val active = if (playfield.upperTouchComparison) "+5 px" else "基线"
        val pending = if (requestedUpperTouchComparison) "+5 px" else "基线"
        if (active == pending) active else "$active（下轮 $pending）"
    }

    internal fun toggleUpperTouchComparison(): Boolean = synchronized(sessionLock) {
        requestedUpperTouchComparison = !requestedUpperTouchComparison
        requestedUpperTouchComparison
    }

    internal fun switchComparisonEngine() {
        synchronized(sessionLock) {
            val chart = comparisonChart ?: return
            val active = mixed ?: return
            captureBoundary("ENGINE_SWITCH", updateSnapshot)
            active.trace = null
            mixed = if (active is PocPlayback) MixedSession(chart, active.startUs, active.endUs,
                openingTapEarlyUs = openingTapEarlyUs)
                else PocPlayback(chart, active.startUs, active.endUs, openingTapEarlyUs = openingTapEarlyUs)
            attachPlayCapture()
            restart()
        }
    }
    internal fun loadLocalAudio(source: PcmSource, pcm: java.io.File) {
        val chart = com.longniang.instrument.sus.NoteResolver.resolve(
            com.longniang.instrument.sus.ControlledTapChart.bytes())
        val result = com.longniang.instrument.sus.TapChartAdapter.adapt(chart)
        check(result.playable)
        loadLocalAudio(source, result.notes, result.columns, pcm)
    }

    private fun loadLocalAudio(source: PcmSource, notes: List<Tap>, columns: Int, pcm: java.io.File) {
        synchronized(sessionLock) {
            discardAudioLocked()
            mixed = null
            comparisonChart = null
            playfield = playfield.copy(columns = columns)
            pcmSource = source
            ownedPcm = pcm
            val loop = PcmLoop(96_000, 288_000, PcmSignal.sampleRate, source.totalFrames)
            practiceLoop = loop
            audioMode = true
            session = TapSession(notes.filter { it.timeUs in loop.startUs until loop.endUs },
                initialGeneration = session.generation, columns = columns)
            pointers.isolate(); resetFeedback()
        }
        publishStatus()
    }

    internal fun loadMixedAudio(source: PcmSource, chart: MixedChart, mode: PracticeMode,
                                pcm: java.io.File, canCompare: Boolean, chartId: String,
                                segment: PracticeSegment? = null, title: String? = null, prepared: MixedPlayback? = null) {
        synchronized(sessionLock) {
            val loop = mode.loop(source.totalFrames)
            val start = segment?.startUs ?: loop?.startUs ?: 0L
            val end = segment?.endUs ?: loop?.endUs ?: SampleTime.us(source.totalFrames, PcmSignal.sampleRate)
            val earlyUs = if (segment == null && loop == null && start == 0L) chart.openingTapEarlyUs() else 0L
            val playback = prepared ?: if (mode.poc) PocPlayback(chart, start, end, openingTapEarlyUs = earlyUs)
                else MixedSession(chart, start, end, openingTapEarlyUs = earlyUs)
            captureBoundary("LOAD_CHART", updateSnapshot)
            discardAudioLocked()
            playfield = playfield.copy(columns = chart.columns)
            ownedPcm = pcm
            practiceLoop = loop
            practiceSegment = segment
            practiceTitle = title
            mixed = playback
            openingVisualLeadUs = OpeningPcmSource.visualLeadUs(playback, noteSpeed, segment == null && loop == null)
            openingTapEarlyUs = earlyUs
            pcmSource = OpeningPcmSource.wrap(source, openingVisualLeadUs)
            comparisonChart = chart.takeIf { canCompare }
            playChartId = chartId
            notePerformanceSession(title, chartId, mode)
            playRules = chart.rules
            attachPlayCapture()
            session = TapSession(emptyList(), initialGeneration = session.generation, columns = chart.columns)
            audioMode = true
            pointers.isolate(); resetFeedback()
        }
        publishStatus()
    }

    internal fun isSegmentPractice() = synchronized(sessionLock) { practiceSegment != null }

    internal fun deleteSegmentPcm(file: java.io.File) = synchronized(sessionLock) {
        val retained = retainedSegmentPlayer?.takeIf { it.first == file }?.second
        if (retained != null) retainedSegmentPlayer = null
        deletePcmAfterRelease(file, retained)
    }

    internal fun pauseForMenu() = synchronized(sessionLock) {
        val output = player
        if (output != null && output.isPlaying && !output.pending) {
            cancelInputs(updateSnapshot?.chartTimeUs)
            hitSounds.reset()
            output.pause()
        }
    }

    internal fun resumeFromMenu() = synchronized(sessionLock) {
        player?.takeIf { !it.pending && it.reading.playbackState == "PAUSED" }?.play()
        Unit
    }

    internal fun toggleSegmentPause(): Boolean = synchronized(sessionLock) {
        if (practiceSegment == null) return@synchronized false
        val output = player ?: return@synchronized false
        if (output.pending || output.reading.playbackState !in listOf("PLAYING", "PAUSED"))
            return@synchronized false
        if (output.isPlaying) {
            cancelInputs(updateSnapshot?.chartTimeUs)
            hitSounds.reset()
            output.pause()
        } else output.play()
        true
    }

    fun loadControlledSus(): String {
        val chart = com.longniang.instrument.sus.NoteResolver.resolve(
            com.longniang.instrument.sus.ControlledTapChart.bytes())
        val result = com.longniang.instrument.sus.TapChartAdapter.adapt(chart)
        if (!result.playable) return result.diagnostics.first { it.error }.message
        synchronized(sessionLock) {
            discardAudioLocked()
            mixed = null
            comparisonChart = null
            audioMode = false
            playfield = playfield.copy(columns = 4)
            session = TapSession(result.notes, initialGeneration = session.generation)
            restart()
        }
        return "已加载 M3-2 SUS 测试谱 · TEST"
    }

    fun switchMode() {
        synchronized(sessionLock) {
            discardAudioLocked()
            mixed = null
            comparisonChart = null
            pcmSource = TestPcmSource; practiceLoop = PcmLoop.practice
            playfield = playfield.copy(columns = 4)
            audioMode = !audioMode
            session = TapSession(if (audioMode) AudioMockChart.notes.filter { it.timeUs >= PcmLoop.practice.startUs && it.timeUs < PcmLoop.practice.endUs } else MockChart.notes,
                initialGeneration = session.generation)
            restart()
        }
    }
    private var inputBridge = captureInputBridge()
    private val pointers = ActivePointers()
    private fun captureInputBridge(): InputTimeBridge {
        val before = System.nanoTime()
        val uptime = SystemClock.uptimeMillis()
        return InputTimeBridge.capture(before, uptime, System.nanoTime())
    }
    private val visuals = TransientFeedback()
    private val seen = mutableMapOf<String, Feedback>()
    private val hitSounds = HitSounds(context)
    private val sustainFeedback = SustainFeedback()
    private val submitSustain: (String, Boolean) -> Unit = hitSounds::sustain
    private var switchingHitBackend = false
    internal fun actualHitBackend(): HitSoundBackend? = synchronized(sessionLock) {
        if (switchingHitBackend || hitSounds.volume <= 0f) null else hitSounds.backendStatus.actual
    }
    internal fun hitBackendDescription(): String = synchronized(sessionLock) {
        when {
            switchingHitBackend -> "切换中"
            hitSounds.volume <= 0f -> "未运行（静音）"
            else -> hitSounds.backendStatus.description
        }
    }
    internal fun switchHitBackend(backend: HitSoundBackend): Boolean {
        synchronized(sessionLock) {
            if (!hasPractice) return false
            switchingHitBackend = true
            hitSounds.requestedBackend = backend
            sustainFeedback.reset()
            hitSounds.reset()
        }
        restart()
        return true
    }
    internal var hitGroup: HitSoundGroup
        get() = hitSounds.group
        set(value) {
            synchronized(sessionLock) {
                hitSounds.group = value
                sustainFeedback.reset()
                hitSounds.reset()
            }
        }
    private var startRequest = 0L
    private val noteFeedback = NoteFeedback().apply { reset(session.generation) }
    internal fun setHitVolume(volume: Float) {
        synchronized(sessionLock) {
            if (volume <= 0f) sustainFeedback.reset()
            hitSounds.setVolume(volume, resumeSound = updateSnapshot?.canJudge == true)
        }
    }
    internal fun releaseHitSounds() = hitSounds.release()
    private fun resetFeedback() {
        pendingMoves.clear()
        handler.removeCallbacks(flushMoves)
        visuals.clear()
        seen.clear()
        noteFeedback.reset(session.generation, mixed)
        sustainFeedback.reset()
        hitSounds.reset()
        recordedResults = 0
        feedbackRequests = 0
        nextEngineSnapshot = 0
        lastSettlement = "none"
        lastFeedback = "none"
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackDrawing = TrackDrawing()
    private val stageBackdrop = StageBackdrop()
    private val mirrorStage = MirrorStage()
    @Volatile internal var stageImage: StageImage? = null
    internal val stageImageSettings = StageImageSettings(StageImageMode.MIRROR)
    internal fun loadStageImage(assets: android.content.res.AssetManager) {
        Thread({
            runCatching {
                assets.open("theme/stage.jpg").use {
                    StageImage.prepare(requireNotNull(android.graphics.BitmapFactory.decodeStream(it)), .56f)
                }
            }.onSuccess { stageImage = it }.onFailure {
                android.util.Log.w("LongNiangStage", "Fixed stage image unavailable", it)
            }
        }, "StageImageLoader").start()
    }
    @Volatile internal var trackStyle = TrackStyle.PERSPECTIVE
    private val handler = Handler(Looper.getMainLooper())
    private val pendingMoves = MoveDispatchQueue()
    private val flushMoves = Runnable {
        synchronized(sessionLock) {
            val snapshot = currentSnapshot(System.nanoTime())
            synchronizeLoop(snapshot)
            synchronizeInputEpoch(snapshot)
            pendingMoves.dispatchThrough(Long.MAX_VALUE)
        }
    }
    @Volatile private var renderer: Thread? = null
    @Volatile private var drawing = false
    @Volatile internal var noteSpeed = NoteSpeed.DEFAULT
        private set
    private var lastAction = "准备"
    private var lastStatusMs = 0L
    @Volatile internal var showHitEffects = true
    private val playCaptureDir = java.io.File(context.filesDir, "play-diagnostics")
    private var playCapture: PlayCapture? = null
    private var playChartId: String? = null
    private var playRules: MixedRules? = null
    private var playContext: ClockSnapshot? = null
    private var playClockKey: List<Any?>? = null
    private var lastCaptureCanJudge: Boolean? = null
    private var capturedAdvanceToken: ClockToken? = null
    private var capturedWatermark: Long? = null

    internal fun isCapturingPlay() = synchronized(sessionLock) { playCapture?.isRecording == true }
    internal fun playCaptureStatus() = synchronized(sessionLock) { playCapture?.status ?: "未记录" }
    internal fun startPlayCapture() = synchronized(sessionLock) {
        if (playCapture?.isRecording != true) {
            playCapture = PlayCapture(playCaptureDir)
            playClockKey = null
            capturedAdvanceToken = null
            attachPlayCapture()
        }
    }
    internal fun stopPlayCapture() = synchronized(sessionLock) {
        if (playCapture?.isRecording == true) captureBoundary("STOP_CAPTURE", updateSnapshot)
        mixed?.trace = null
        playCapture?.stop()
    }
    internal fun playCaptureFile(name: String): java.io.File? =
        java.io.File(playCaptureDir, name).takeIf { name == it.name && name.startsWith("play-") &&
            name.endsWith(".csv") && it.isFile }

    internal fun exportPlayCapture(onReady: (java.io.File?) -> Unit) {
        val capture = synchronized(sessionLock) { stopPlayCapture(); playCapture }
        Thread({
            capture?.await()
            val file = if (capture != null) playCaptureFile("${capture.id}.csv") else
                playCaptureDir.listFiles()?.filter { it.name.startsWith("play-") && it.extension == "csv" }
                    ?.maxByOrNull { it.name }
            onReady(file)
        }, "PlayCaptureExport").start()
    }

    private fun attachPlayCapture() {
        val capture = playCapture?.takeIf { it.isRecording } ?: return
        val playback = mixed ?: return
        val byId = playback.actions.associateBy { it.id }
        playContext = updateSnapshot
        capture.row("PLAN", session.generation, null, null, System.nanoTime(), playChartId, null,
            if (playback is PocPlayback) "poc-b1" else "legacy-b1", playback.startUs, playback.endUs,
            playRules, noteSpeed.label, "System.nanoTime/ns; MotionEvent/uptime ns; chart/us; offsetUs=${(pcmSource as? OpeningPcmSource)?.offsetUs ?: 0}",
            "upperTouchExtraRefPx=${playfield.extraUpperTouchReferencePx}", practiceTitle,
            if (practiceSegment != null) "SEGMENT" else null)
        for (a in playback.actions) capture.row("EVENT", null, null, a.at, null, a.id, null,
            a.event.objectId, a.event.action, a.span.left, a.span.right, a.direction, a.isTail, a.critical,
            a.path?.kind, a.event.origin, playRules?.window(a), a.event.sourceIds.joinToString("|"))
        for (path in playback.paths) path.nodes.forEachIndexed { i, node ->
            capture.row("NODE", null, null, node.timeUs, null, path.id, null,
                i, node.role, node.interval.left, node.interval.right, node.subject.timing.exactBeat,
                node.sources.joinToString("|") { it.id })
        }
        playback.trace = com.longniang.judgment.JudgmentTrace { kind, at, id, pointer, detail ->
            val s = playContext
            val action = byId[id]
            capture.row(kind, s?.token?.generation ?: session.generation, s?.token?.revision, at,
                s?.monotonicNs, id, pointer, detail, action?.at, action?.event?.objectId,
                action?.span?.left, action?.span?.right, action?.event?.action, action?.let { at - it.at })
        }
    }

    private fun captureBoundary(kind: String, s: ClockSnapshot?) {
        val capture = playCapture?.takeIf { it.isRecording } ?: return
        capture.row(kind, session.generation, s?.token?.revision, s?.chartTimeUs, System.nanoTime(), playChartId,
            null, "snapshotGeneration=${s?.token?.generation}", "resolved=${mixed?.results?.size}",
            "planned=${mixed?.actions?.size}", noteSpeed.label, player?.diagnosticRoute,
            "upperTouchExtraRefPx=${playfield.extraUpperTouchReferencePx}")
    }

    private fun captureClock(s: ClockSnapshot) {
        val capture = playCapture?.takeIf { it.isRecording } ?: return
        playContext = s
        val route = player?.diagnosticRoute
        val key = listOf(s.token, s.state, s.reason, route, noteSpeed.label)
        if (key == playClockKey) return
        playClockKey = key
        capture.row("CLOCK", s.token.generation, s.token.revision, s.chartTimeUs, s.monotonicNs,
            playChartId, null, s.state, s.reason, route, noteSpeed.label, s.source,
            player?.reading?.snapshot(s.monotonicNs)?.chartTimeUs, "resolved=${mixed?.results?.size}")
    }

    private val freezeTrace = if (context.applicationInfo.flags and
        android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) FreezeTrace() else null
    private val uiThread = Thread.currentThread()
    @Volatile private var updateSnapshot: ClockSnapshot? = null
    @Volatile private var updateCompletedNs = 0L
    @Volatile private var canvasPostedNs = 0L
    @Volatile private var postedChartUs = 0L
    @Volatile private var inUpdate = "idle"
    @Volatile private var inRender = "idle"
    @Volatile private var engineState = "unavailable"
    @Volatile private var lastSettlement = "none"
    @Volatile private var lastFeedback = "none"
    private var nextEngineSnapshot = 0L
    private var recordedResults = 0
    private var feedbackRequests = 0
    private val freezeWatch = freezeTrace?.let {
        FreezeWatchdog(java.io.File(context.cacheDir, "freeze"), it, ::freezeSample, ::freezeDetail)
    }

    internal fun captureFreeze() { freezeWatch?.capture() }
    internal fun closeFreezeWatch() { freezeWatch?.close() }

    private fun freezeSample(nowNs: Long): FreezeSample? {
        if (!drawing) return null
        val audio = player ?: return null
        val raw = audio.progress ?: return null
        val snapshot = updateSnapshot ?: return null
        if (raw.generation != audioRunGeneration || nowNs - raw.sampledNs > 500_000_000L ||
            audio.reading.playbackState != "PLAYING") return null
        val frame = raw.headFrame ?: raw.rawFrame?.takeIf { raw.rawOk == true } ?: return null
        return FreezeSample(snapshot.token.generation, frame, snapshot.chartTimeUs,
            updateCompletedNs, canvasPostedNs, nowNs, snapshot.canJudge)
    }

    private fun freezeDetail(): String {
        val now = System.nanoTime()
        val audio = player
        val reading = audio?.reading
        val renderThread = renderer
        fun stack(thread: Thread?) = thread?.stackTrace?.take(16)?.joinToString("\n").orEmpty()
        return "nowNs=$now runGeneration=$audioRunGeneration\n" +
            "audioRaw=${audio?.progress} audioAnchor=${reading?.anchor} phase=${reading?.phase}\n" +
            "gameClock=$updateSnapshot lastUpdateNs=$updateCompletedNs stage=$inUpdate\n" +
            "lastCanvasPostNs=$canvasPostedNs chart=$postedChartUs stage=$inRender\n" +
            "threads UI/update=${uiThread.state} Renderer=${renderThread?.state} Audio=${audio?.workerState()}\n" +
            "$engineState\nlastSettlement=$lastSettlement\nlastFeedback=$lastFeedback\n" +
            "UI stack:\n${stack(uiThread)}\nRenderer stack:\n${stack(renderThread)}"
    }

    private fun recordInput(action: String, pointer: Int, point: MixedPoint, input: TimedInput, sampleNs: Long) {
        freezeTrace?.record("INPUT $action g=${input.token.generation} r=${input.token.revision}" +
            " chart=${input.chartTimeUs} sampleNs=$sampleNs p=$pointer lane=${point.lane} x=${point.x} y=${point.y}")
    }

    private fun recordEngine(playback: MixedPlayback, generation: Long, now: Long) {
        val trace = freezeTrace ?: return
        if (playback.results.size != recordedResults) {
            val start = recordedResults
            playback.results.values.forEachIndexed { i, result ->
                if (i >= start) {
                    val event = result.action
                    lastSettlement = "g=$generation event=${event.id} object=${event.event.objectId}" +
                        " target=${event.at} delta=${result.deltaUs} grade=${result.grade}"
                    trace.record("SETTLE $lastSettlement")
                }
            }
            recordedResults = playback.results.size
        }
        if (now >= nextEngineSnapshot) {
            nextEngineSnapshot = now + 100_000_000L
            engineState = playback.freezeState()
        }
    }
    var onStatus: ((String) -> Unit)? = null
    // Delays MISS publication, never widens the hit window.
    private val dispatchGraceUs = 100_000L
    private val tick = object : Runnable {
        override fun run() {
            val uptime = SystemClock.uptimeMillis()
            val perf = performanceCapture
            val tickStart = if (perf != null) System.nanoTime() else 0L
            inUpdate = "tick waiting for sessionLock"
            synchronized(sessionLock) {
                val snapshot = currentSnapshot(System.nanoTime())
                inUpdate = "tick epoch"
                updateSnapshot = snapshot
                synchronizeInputEpoch(snapshot)
                val currentMixed = mixed
                if (currentMixed != null && snapshot.token.generation == session.generation &&
                    (openingVisualLeadUs == 0L || snapshot.chartTimeUs >= 0) &&
                    (snapshot.canJudge || (snapshot.valid && snapshot.reason == "ENDED"))) {
                    inUpdate = "tick judgment"
                    val watermark = snapshot.chartTimeUs - if (snapshot.canJudge) dispatchGraceUs else 0
                    val capture = playCapture?.takeIf { it.isRecording }
                    if (capture != null && (snapshot.token != capturedAdvanceToken || watermark != capturedWatermark)) {
                        capture.row("ADVANCE", snapshot.token.generation, snapshot.token.revision, watermark,
                            snapshot.monotonicNs, null, null)
                        capturedAdvanceToken = snapshot.token
                        capturedWatermark = watermark
                    }
                    currentMixed.advance(watermark)
                } else if (audioMode && currentMixed == null) AudioTapInput.advance(session, snapshot)
                else if (snapshot.canJudge) session.advance(snapshot.chartTimeUs - dispatchGraceUs)
                collectJudgements(snapshot, uptime, null)
                noteFeedback.prune(snapshot.chartTimeUs)
                visuals.prune(uptime)
                updateCompletedNs = System.nanoTime()
                inUpdate = "idle"
            }
            if (uptime - lastStatusMs >= 100) publishStatus()
            if (perf != null) {
                performanceUpdateNs = System.nanoTime() - tickStart
                if (uptime >= performanceNextGcMs) {
                    perf.gcCount = android.os.Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: -1
                    perf.gcTimeMs = android.os.Debug.getRuntimeStat("art.gc.gc-time")?.toLongOrNull() ?: -1
                    performanceNextGcMs = uptime + 1_000
                }
            }
            if (drawing) handler.postDelayed(this, 8)
        }
    }
    init {
        holder.addCallback(this)
        isFocusable = true
        contentDescription = "节奏游戏轨道；右上角打开游戏菜单"
    }
    fun restart() {
        val request = synchronized(sessionLock) {
            if (!hasPractice) return
            ++startRequest
        }
        if (hitSounds.awaitPreload { startPrepared(request) }) {
            lastAction = "正在预载打击音效"
            publishStatus()
        } else startPrepared(request)
    }

    private fun startPrepared(request: Long) {
        synchronized(sessionLock) {
            if (!hasPractice || request != startRequest) return
            captureBoundary("RESET_PREVIOUS_ROUND", updateSnapshot)
            playfield = playfield.copy(upperTouchComparison = requestedUpperTouchComparison && mixed != null && playfield.columns == 12)
            session.reset()
            mixed?.reset()
            if (audioMode) {
                audioRunGeneration = session.generation
                audioPlayer().restart(audioRunGeneration)
            }
            else clock.resetAt(System.nanoTime(), session.generation)
            inputBridge = captureInputBridge(); pointers.isolate()
            resetFeedback()
            switchingHitBackend = false
            val status = hitSounds.backendStatus
            android.util.Log.i("LongNiangHit", "practice g=${session.generation} " +
                "actualBackend=${actualHitBackend() ?: "NONE"} requestedBackend=${hitSounds.requestedBackend} " +
                "preload=${status.phase} group=${hitSounds.group}")
            freezeTrace?.record("START g=${session.generation} mode=${mixed?.javaClass?.simpleName} loop=$practiceLoop")
            captureBoundary("START", null)
            lastAction = "已重置并开始 · 第${session.generation}轮"
        }
        publishStatus()
    }
    fun endPractice(retainPcm: Boolean = false) {
        synchronized(sessionLock) {
            if (!hasPractice && player == null && ownedPcm == null) return
            captureBoundary("END_PRACTICE", updateSnapshot)
            discardAudioLocked(retainPcm)
            cancelInputs()
            mixed = null
            comparisonChart = null
            playChartId = null
            session = TapSession(emptyList(), initialGeneration = session.generation)
            clock.invalidateAt(System.nanoTime(), "PRACTICE_ENDED")
            audioMode = false
            audioRunGeneration = 0
            observedToken = null
            pcmSource = TestPcmSource
            practiceLoop = PcmLoop.practice
            playfield = playfield.copy(columns = 4, upperTouchComparison = false)
            requestedUpperTouchComparison = false
            resetFeedback()
            hitSounds.end()
            lastAction = "练习已结束"
        }
        publishStatus()
    }
    private fun discardAudioLocked(retainPcm: Boolean = false) {
        startRequest++
        openingVisualLeadUs = 0L
        openingTapEarlyUs = 0L
        switchingHitBackend = false
        if (hitSounds.requestedBackend != HitSoundBackend.PCM ||
            hitSounds.backendStatus.backend == HitSoundBackend.SOUND_POOL) hitSounds.end()
        hitSounds.requestedBackend = HitSoundBackend.PCM
        val old = player
        val temporary = ownedPcm
        practiceSegment = null
        practiceTitle = null
        player = null
        ownedPcm = null
        old?.release()
        if (temporary != null) {
            if (retainPcm && old != null) retainedSegmentPlayer = temporary to old
            if (!retainPcm) {
                val retained = retainedSegmentPlayer?.takeIf { it.first == temporary }?.second
                if (retained != null) retainedSegmentPlayer = null
                deletePcmAfterRelease(temporary, old ?: retained)
            }
        }
    }
    private fun deletePcmAfterRelease(file: java.io.File, old: PcmAudioPlayer?) {
        if (old == null) file.delete()
        else Thread({ old.awaitRelease(); file.delete() }, "PracticePcmCleanup").start()
    }
    internal fun setNoteSpeed(value: NoteSpeed) {
        noteSpeed = value
    }
    private fun publishStatus() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val message: String
        synchronized(sessionLock) {
            val s = currentSnapshot(System.nanoTime())
            message = "${if (audioMode) "AUDIO" else "TEST"} ${s.state} · chart ${s.chartTimeUs / 1000}ms · 流速 ${noteSpeed.label}\n" +
                (mixed?.let { "音符 HIT ${it.objectResults.values.count { f -> f.grade != Grade.MISS }} / MISS ${it.objectResults.values.count { f -> f.grade == Grade.MISS }} · 事件 ${it.results.size}/${it.actions.size} · Note Combo ${noteFeedback.combo} · 事件 Combo ${it.combo} · ${it.points} pt\n" }
                    ?: "HIT ${session.results.values.count { it == Result.HIT }} / MISS ${session.results.values.count { it == Result.MISS }} · Δ${session.feedback.lastOrNull { it.result == Result.HIT }?.deltaUs?.div(1000) ?: "--"}ms\n") +
                "g/r ${s.token.generation}/${s.token.revision} · ${if (hasPractice) s.reason ?: lastAction else lastAction}"
        }
        lastStatusMs = SystemClock.uptimeMillis()
        onStatus?.invoke(message)
    }
    fun diagnosticText(): String = synchronized(sessionLock) {
        val s = currentSnapshot(System.nanoTime())
        val r = player?.reading
        "模式=${if (audioMode) "AUDIO" else "TEST"} chart=${s.chartTimeUs} us offset=${(pcmSource as? OpeningPcmSource)?.offsetUs ?: 0} us\n" +
            "generation/revision=${s.token.generation}/${s.token.revision} · ${s.state}\n" +
            (mixed?.let {
                val observed = it.comboObservation
                val summary = observed?.summary(it.results)
                "音符 ${it.objectResults.size}/${it.objectCount} · 事件 ${it.results.size}/${it.actions.size}\n" +
                    "Note Combo ${noteFeedback.combo} · 事件 Combo ${it.combo}\n" +
                    if (observed != null && summary != null)
                        "Combo 研究累计 ${summary.confirmed}/${observed.planned} · 未通过 ${summary.missed}" +
                            " · 未知 ${summary.unknown} · 待结算 ${summary.pending} · 来源 " +
                            (if (observed.upstreamVerified) "已核对上游\n" else "仅补丁谱面\n")
                    else ""
            } ?: "") +
            ((mixed as? PocPlayback)?.diagnosticText()?.plus("\n") ?: "") +
            "下一Tap=${session.notes.firstOrNull { session.results[it.id] == Result.PENDING }?.timeUs ?: "--"} us · hitDelta=${session.feedback.lastOrNull { it.result == Result.HIT }?.deltaUs ?: "--"} us\n" +
            "renderCount=$renderCount · lastInterval=${renderIntervalNs / 1000} us · renderChart=$lastRenderChartUs us\n" +
            "invalidations=${r?.invalidations ?: 0} · establishments=${r?.establishments ?: 0}（当前实例）\n" +
            hitSounds.diagnostics()
    }
    private fun collectJudgements(snapshot: ClockSnapshot, uptime: Long, sampleNs: Long?) {
        val engineReturnedNs = System.nanoTime()
        val generation = snapshot.token.generation
        if (generation != session.generation || !(snapshot.canJudge || (snapshot.valid && snapshot.reason == "ENDED"))) return
        val onHit: (HitEvent) -> Unit = { event ->
            freezeTrace?.let {
                feedbackRequests++
                lastFeedback = "event=${event.id} cue=${event.cue} requests=$feedbackRequests" +
                    " noteCombo=${noteFeedback.combo} audioMuted=${hitSounds.volume <= 0f} visual=$showHitEffects"
                it.record("FEEDBACK $lastFeedback")
            }
            if (!switchingHitBackend) hitSounds.play(event, SoundTiming(sampleNs, engineReturnedNs))
        }
        mixed?.let { chart ->
            inUpdate = "publish judgment"
            recordEngine(chart, generation, engineReturnedNs)
            inUpdate = "publish feedback"
            noteFeedback.collect(chart, generation, snapshot.chartTimeUs, onHit)
            sustainFeedback.sync(chart, snapshot.chartTimeUs,
                snapshot.canJudge && !switchingHitBackend && hitSounds.wantsSustain, submitSustain)
            if (seen.size != chart.objectResults.size) for ((id, f) in chart.objectResults) {
                val result = if (f.grade == Grade.MISS) Result.MISS else Result.HIT
                val feedback = Feedback(id, result, 0)
                if (seen.put(id, feedback) != feedback && result == Result.MISS)
                    visuals.judgement(f.action.span.center.toInt().coerceIn(0, playfield.columns - 1), result, uptime)
            }
            inUpdate = "idle"
            return
        }
        for (f in session.feedback) if (seen[f.id] != f) {
            seen[f.id] = f
            val note = session.notes.first { it.id == f.id }
            noteFeedback.tap(note, if (f.result == Result.HIT) Grade.PERFECT else Grade.MISS,
                generation, snapshot.chartTimeUs, onHit)
            if (f.result == Result.MISS) visuals.judgement(note.lane, f.result, uptime)
        }
    }
    override fun surfaceCreated(h: SurfaceHolder) {
        stopRenderer()
        drawing = true
        synchronized(sessionLock) { if (session.generation == 1L) restart() }
        handler.removeCallbacks(tick); handler.post(tick)
        startRenderer(h)
    }

    private fun startRenderer(h: SurfaceHolder) {
        if (!drawing || renderer?.isAlive == true) return
        val surface = h.surface
        renderer = Thread({
            while (drawing && surface.isValid) {
                val start = System.nanoTime()
                val perfSession = performanceSessionId
                val perf = performanceCapture.takeIf { perfSession != 0 }
                var acquired = 0L
                var drawn = 0L
                inRender = "lockCanvas"
                val keepDrawing = drawSurfaceFrame<Canvas>(
                    acquire = { if (drawing) h.lockHardwareCanvas() else null },
                    draw = {
                        if (drawing) {
                            if (perf != null) acquired = System.nanoTime()
                            inRender = "render"
                            render(it, perf != null)
                            if (perf != null) drawn = System.nanoTime()
                        }
                    },
                    post = {
                        inRender = "post"
                        h.unlockCanvasAndPost(it)
                        postedChartUs = lastRenderChartUs
                        canvasPostedNs = System.nanoTime()
                        if (drawn != 0L) perf?.frame(start, acquired, drawn, canvasPostedNs, lastRenderChartUs,
                            performanceSnapshotNs, performanceStageNs, performanceFeedbackNs, performanceUpdateNs,
                            performanceGeneration, performanceRevision, perfSession)
                        inRender = "idle"
                    },
                    onSurfaceFailure = {
                        freezeTrace?.record("SURFACE_FAILURE stage=$inRender error=$it")
                        android.util.Log.w("LongNiangRender", "Surface frame failed", it)
                    }
                )
                if (!keepDrawing) break
                val remaining = 8_333_333L - (System.nanoTime() - start)
                if (remaining > 0) try { Thread.sleep(remaining / 1_000_000, (remaining % 1_000_000).toInt()) }
                catch (_: InterruptedException) { break }
            }
        }, "TapRenderer").also { it.start() }
    }

    private fun stopRenderer() {
        drawing = false
        val worker = renderer ?: return
        worker.interrupt()
        var interrupted = false
        while (worker.isAlive) {
            try { worker.join() }
            catch (_: InterruptedException) { interrupted = true }
        }
        renderer = null
        if (interrupted) Thread.currentThread().interrupt()
    }

    override fun surfaceDestroyed(h: SurfaceHolder) {
        stopRenderer()
        handler.removeCallbacks(tick)
        endPractice()
    }

    override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, height: Int) {
        synchronized(sessionLock) {
            playfield = playfield.copy(width = w.toFloat(), height = height.toFloat())
            cancelInputs(); visuals.clear(); noteFeedback.clearFlashes()
        }
        startRenderer(h)
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        inUpdate = "input waiting for sessionLock"
        return try { routeTouchEvent(e) }
        finally { inUpdate = "idle" }
    }

    private fun routeTouchEvent(e: MotionEvent): Boolean {
        synchronized(sessionLock) {
            inUpdate = "input routing"
            if (!hasPractice) return true
            val reading = player?.reading
            val inputClock = activeClock(reading)
            val receivedNs = System.nanoTime()
            val snapshot = inputClock.snapshot(receivedNs)
            synchronizeLoop(snapshot)
            synchronizeInputEpoch(snapshot)
            val token = snapshot.token
            val eventNs = if (Build.VERSION.SDK_INT >= 34) e.eventTimeNanos else e.eventTime * 1_000_000L
            val sampleNs = inputBridge.toMonotonicNs(eventNs)
            val complex = mixed
            val expansion = if (complex != null && playfield.columns == 12) playRules?.spatialExpansion ?: 0f else 0f
            fun laneAt(x: Float, y: Float): Int? =
                if (playfield.width == width.toFloat() && playfield.height == height.toFloat())
                    playfield.practiceLane(x, y, expansion) else null
            val laneWidth = playfield.trackWidth / playfield.columns
            fun point(x: Float, y: Float, lane: Int? = laneAt(x, y)) = playfield.practicePoint(x, y, lane)
            fun mixedSample(at: Long): TimedInput? =
                if (laneWidth <= 0 || receivedNs - at !in 0..AudioTapInput.maxDelayNs ||
                    token.generation != session.generation) null
                else openingTapInput(inputClock, at, receivedNs, token, openingTapEarlyUs)
            val capture = playCapture?.takeIf { it.isRecording }
            fun saveSample(action: String, index: Int, x: Float, y: Float, uptimeNs: Long, at: Long,
                           history: Int?, input: TimedInput?, rejected: String?) {
                if (capture == null) return
                val id = e.getPointerId(index)
                val p = point(x, y)
                val reason = rejected ?: when {
                    receivedNs - at !in 0..AudioTapInput.maxDelayNs -> "DISPATCH_DELAY_OR_FUTURE"
                    input == null -> "CLOCK_OR_EPOCH_REJECTED"
                    !playfield.inPracticeBand(y, expansion) -> "OUT_OF_TOUCH_BAND"
                    p.lane == null -> "OUTSIDE_TRACK_OR_SURFACE"
                    else -> "ROUTED"
                }
                val audio = reading?.let { clockReading ->
                    clockReading.inputAt(at, receivedNs, clockReading.anchor.token)?.chartTimeUs
                        ?.let { AudioOffset.audioUs(it, clockReading.offsetUs) }
                }
                capture.row("INPUT", token.generation, token.revision, input?.chartTimeUs, at, action, id,
                    uptimeNs, receivedNs, x + e.rawX - e.x, y + e.rawY - e.y, x, y, p.laneX,
                    p.lane, pointers[id] != null, history, reason, audio, snapshot.chartTimeUs,
                    snapshot.state, p.x, p.y, input != null && rejected == null, index,
                    pointers[id]?.admitted == true)
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    if (e.actionMasked == MotionEvent.ACTION_DOWN && !pointers.isEmpty) {
                        // A new Android gesture replaces an interrupted stream that never delivered UP/CANCEL.
                        cancelInputs()
                        pointers.clear()
                    }
                    val index = e.actionIndex
                    val id = e.getPointerId(index)
                    val x = e.getX(index)
                    val y = e.getY(index)
                    val lane = laneAt(x, y)
                    val uptime = SystemClock.uptimeMillis()
                    // Android downTime belongs to the whole gesture. Each new pointer
                    // belongs to the epoch containing its own POINTER_DOWN sample.
                    val mapped = if (complex != null) mixedSample(sampleNs)
                        else if (receivedNs - sampleNs in 0..AudioTapInput.maxDelayNs)
                            inputClock.inputAt(sampleNs, receivedNs, token) else null
                    val duplicate = pointers[id] != null
                    saveSample(if (e.actionMasked == MotionEvent.ACTION_DOWN) "ACTION_DOWN" else "ACTION_POINTER_DOWN",
                        index, x, y, eventNs, sampleNs, null, mapped, if (duplicate) "DUPLICATE_DOWN" else null)
                    if (!duplicate) {
                        if (mapped != null && complex != null) pendingMoves.dispatchThrough(sampleNs)
                        val accepted = if (mapped == null) false else if (complex != null) {
                            val p = point(x, y, lane)
                            recordInput("DOWN", id, p, mapped, sampleNs)
                            inUpdate = "input DOWN judgment"
                            complex.down(id, p, mapped.chartTimeUs)
                        } else if (audioMode)
                            AudioTapInput.down(session, inputClock, sampleNs, receivedNs,
                                sampleNs, token, id, lane ?: -1)
                        else SessionInput.down(session, clock, mapped, receivedNs, id, lane ?: -1)
                        pointers.down(PointerState(id, x, y, lane, sampleNs, token, admitted = accepted))
                        capture?.row("POINTER_ADMISSION", token.generation, token.revision, mapped?.chartTimeUs,
                            sampleNs, null, id, if (accepted) "ACCEPTED" else if (mapped == null) "UNMAPPED" else "ENGINE_REJECTED")
                        if (mapped != null) collectJudgements(snapshot, uptime, sampleNs)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (complex != null) {
                        for (history in 0..e.historySize) {
                            val historical = history < e.historySize
                            val nanos = if (historical) {
                                if (Build.VERSION.SDK_INT >= 34) e.getHistoricalEventTimeNanos(history)
                                else e.getHistoricalEventTime(history) * 1_000_000L
                            } else eventNs
                            val at = inputBridge.toMonotonicNs(nanos)
                            val mapped = mixedSample(at)
                            for (index in 0 until e.pointerCount) {
                                val id = e.getPointerId(index)
                                val pointer = pointers[id]
                                val reject = when {
                                    pointer == null -> "NO_ACTIVE_POINTER"
                                    at < pointer.lastSampleNs -> "OLDER_POINTER_SAMPLE"
                                    pointer.token != token -> "POINTER_EPOCH_MISMATCH"
                                    !pointer.admitted -> "PHYSICAL_ONLY"
                                    else -> null
                                }
                                if (pointer == null && capture == null) continue
                                val x = if (historical) e.getHistoricalX(index, history) else e.getX(index)
                                val y = if (historical) e.getHistoricalY(index, history) else e.getY(index)
                                saveSample("ACTION_MOVE", index, x, y, nanos, at,
                                    history.takeIf { historical }, mapped, reject)
                                val lane = laneAt(x, y)
                                pointers.move(id, x, y, lane, at)
                                if (reject != null || mapped == null) continue
                                val p = point(x, y, lane)
                                val lifecycle = pointer?.downNs ?: continue
                                val schedule = pendingMoves.isEmpty
                                pendingMoves.add(at) {
                                    val live = pointers[id]
                                    if (live?.downNs == lifecycle && live.token == token && live.admitted && mixed === complex) {
                                        val settled = complex.results.size
                                        recordInput("MOVE", id, p, mapped, at)
                                        inUpdate = "input MOVE judgment"
                                        complex.move(id, p, mapped.chartTimeUs)
                                        if (complex.results.size != settled)
                                            collectJudgements(snapshot, SystemClock.uptimeMillis(), at)
                                        else inUpdate = "idle"
                                    }
                                }
                                if (schedule) {
                                    handler.removeCallbacks(flushMoves)
                                    handler.postDelayed(flushMoves, MoveDispatchQueue.LOOKAHEAD_MS)
                                }
                            }
                        }
                        pendingMoves.dispatchThrough(sampleNs - MoveDispatchQueue.LOOKAHEAD_MS * 1_000_000L)
                        return true
                    }
                    for (index in 0 until e.pointerCount) {
                        val x = e.getX(index)
                        val y = e.getY(index)
                        pointers.move(e.getPointerId(index), x, y, laneAt(x, y), sampleNs)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    val index = e.actionIndex
                    val id = e.getPointerId(index)
                    val mapped = if (complex != null) mixedSample(sampleNs) else inputClock.inputAt(sampleNs, receivedNs, token)
                    if (complex != null) pendingMoves.dispatchThrough(sampleNs)
                    val state = pointers[id]
                    saveSample(if (e.actionMasked == MotionEvent.ACTION_UP) "ACTION_UP" else "ACTION_POINTER_UP",
                        index, e.getX(index), e.getY(index), eventNs, sampleNs, null, mapped, when {
                            state == null -> "NO_ACTIVE_POINTER"
                            sampleNs < state.lastSampleNs -> "OLDER_POINTER_SAMPLE"
                            state.token != token -> "POINTER_EPOCH_MISMATCH"
                            !state.admitted -> "PHYSICAL_ONLY"
                            mapped == null -> "DISCARD_WITHOUT_MAPPED_TIME"
                            else -> null
                        })
                    if (pointers.up(id, sampleNs) && state?.admitted == true && state.token == token) {
                        if (complex == null) session.up(id)
                        else {
                            if (mapped == null) {
                                freezeTrace?.record("INPUT DISCARD g=${token.generation} p=$id sampleNs=$sampleNs")
                                complex.discard(id)
                            } else {
                                val x = e.getX(index)
                                val y = e.getY(index)
                                val releaseLane = if (playfield.width == width.toFloat() && playfield.height == height.toFloat())
                                    playfield.releaseLane(x, y, expansion) else null
                                val p = playfield.practicePoint(x, y, laneAt(x, y), releaseLane)
                                recordInput("UP", id, p, mapped, sampleNs)
                                inUpdate = "input UP judgment"
                                complex.up(id, p, mapped.chartTimeUs)
                            }
                            collectJudgements(snapshot, SystemClock.uptimeMillis(), sampleNs)
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (capture != null) for (index in 0 until e.pointerCount)
                        saveSample("ACTION_CANCEL", index, e.getX(index), e.getY(index), eventNs, sampleNs,
                            null, null, "CANCEL_ALL")
                    freezeTrace?.record("INPUT CANCEL g=${token.generation} chart=${snapshot.chartTimeUs} sampleNs=$sampleNs")
                    cancelInputs()
                    pointers.clear()
                }
            }
        }
        return true
    }
    private fun render(c: Canvas, measure: Boolean) {
        val snapshotStart = if (measure) System.nanoTime() else 0L
        val now: Long
        val speed: NoteSpeed
        val results: Map<String, Result>
        val pulses: List<VisualPulse>
        val board: Playfield
        val notes: List<Tap>
        val mixedFrame: MixedFrame?
        val flashes: List<HitFlash>
        val noteCombo: Int?
        val pressedLanes: Set<Int>
        val uptime = SystemClock.uptimeMillis()
        inRender = "snapshot waiting for sessionLock"
        synchronized(sessionLock) {
            inRender = "snapshot under sessionLock"
            val renderNs = System.nanoTime()
            val snapshot = currentSnapshot(renderNs)
            val chartUs = if (snapshot.reason == "NOT_STARTED") -openingVisualLeadUs else snapshot.chartTimeUs
            now = OpeningPcmSource.visualTimeUs(chartUs, openingVisualLeadUs)
            speed = noteSpeed
            if (measure) {
                performanceGeneration = snapshot.token.generation
                performanceRevision = snapshot.token.revision
            }
            renderCount++; renderIntervalNs = if (previousRenderNs == 0L) 0 else renderNs - previousRenderNs
            previousRenderNs = renderNs; lastRenderChartUs = snapshot.chartTimeUs
            notes = session.notes
            mixedFrame = mixed?.let { playback ->
                val contacts = if (showHitEffects && snapshot.canJudge && snapshot.token == observedToken)
                    playback.paths.filter { now in it.start until it.end && playback.hasContact(it.id) }
                        .mapTo(hashSetOf()) { it.id } else emptySet()
                MixedFrame(playback.actions, playback.paths, playback.results.keys.toSet(),
                    playback.startUs, playback.endUs, contacts, playback.guides)
            }
            flashes = if (showHitEffects) noteFeedback.visible(now) else emptyList()
            noteCombo = noteFeedback.combo.takeIf { hasPractice }
            pressedLanes = pointers.pressedLanes()
            results = session.results.toMap(); pulses = visuals.visible(uptime); board = playfield
        }
        val stageStart = if (measure) System.nanoTime() else 0L
        if (measure) {
            performanceSnapshotNs = stageStart - snapshotStart
            performanceStageNs = 0
            performanceFeedbackNs = 0
        }
        val w = c.width.toFloat(); val h = c.height.toFloat()
        if (board.width != w || board.height != h) return // Wait for surfaceChanged snapshot.
        if (w <= 0 || h <= 0) return
        val top = board.spawnY; val line = board.lineY
        val projection = NoteProjection(board, now, speed, trackStyle)
        inRender = "draw stage"
        stageBackdrop.draw(c, w, h)
        val settings = stageImageSettings
        val image = if (settings.mode == StageImageMode.GRADIENT) null else stageImage
        if (image != null) mirrorStage.background(c, board, image, settings.backgroundBrightness)
        if (image != null && settings.mode == StageImageMode.MIRROR)
            trackDrawing.stage(c, paint, board, projection, pressedLanes, mirrorStage, image, settings)
        else trackDrawing.stage(c, paint, board, projection, pressedLanes)
        val feedbackStart = if (measure) System.nanoTime() else 0L
        if (measure) performanceStageNs = feedbackStart - stageStart
        inRender = "draw feedback"
        trackDrawing.hits(c, paint, board, projection, flashes)
        if (noteCombo != null) trackDrawing.combo(c, paint, board, noteCombo)
        for (pulse in pulses) {
            paint.color = if (pulse.result == Result.HIT) Color.GREEN else Color.rgb(255, 110, 110)
            paint.alpha = (230 * pulse.remaining(uptime)).toInt().coerceIn(0, 230)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = minOf(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 17f, resources.displayMetrics), board.trackWidth / 12)
            val y = line + (h - line) * .53f
            c.drawText(pulse.result.name, projection.xAt(pulse.lane + .5f, y), y, paint)
        }
        paint.alpha = 255
        paint.textAlign = Paint.Align.LEFT
        if (measure) performanceFeedbackNs = System.nanoTime() - feedbackStart
        inRender = "draw notes"
        if (mixedFrame != null) trackDrawing.mixed(c, paint, board, mixedFrame, projection)
        val saved = c.save()
        c.clipRect(0f, top, w, h)
        for (note in notes) if (results[note.id] == Result.PENDING) {
            trackDrawing.note(c, paint, board, projection, note.lane.toFloat(),
                (note.lane + note.width).toFloat(), note.timeUs)
        }
        c.restoreToCount(saved)
    }
}















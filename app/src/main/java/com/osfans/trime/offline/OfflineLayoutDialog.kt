package com.osfans.trime.offline

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.keyboard.Keyboard
import splitties.dimensions.dp
import kotlin.math.min

/** Draft sliders never write preferences; Save applies the whole layout together. */
object OfflineLayoutDialog {
    private data class Setting(val key: String, val title: String, val min: Int, val max: Int, val default: Int, val unit: String)
    private val settings = listOf(
        Setting("offline_height", "键盘高度", 70, 160, 100, "%"),
        Setting("offline_width", "键盘宽度", 65, 100, 100, "%"),
        Setting("offline_position", "左右位置（0 最左，100 最右）", 0, 100, 50, ""),
        Setting("offline_bottom", "底部留白", 0, 100, 0, " dp"),
        Setting("offline_text", "按键字号", 70, 150, 100, "%"),
        Setting("offline_candidate_height", "候选栏高度", 40, 96, 56, " dp"),
        Setting("offline_pinyin_height", "放大后的拼音栏高度", 48, 120, 64, " dp"),
        Setting("offline_pinyin_text", "放大后的拼音字号", 18, 40, 26, " sp"),
    )
    fun show(context: Context) {
        val values = settings.associate { it.key to OfflinePrefs.number(it.key, it.default).coerceIn(it.min, it.max) }.toMutableMap()
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        val preview = LayoutPreview(context, values)
        val hint = TextView(context).apply { text = "拖动滑块实时预览；点拼音切换放大，可试左右光标。保存后收起再打开键盘生效。"; textSize = 14f }
        container.addView(hint)
        val labels = mutableMapOf<String, TextView>()
        val sliders = mutableMapOf<String, SeekBar>()
        val controls = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        settings.forEach { setting ->
            val label = TextView(context).apply { textSize = 16f; text = "${setting.title}：${values[setting.key]}${setting.unit}" }
            labels[setting.key] = label
            controls.addView(label)
            val slider = SeekBar(context).apply {
                max = setting.max - setting.min
                progress = values.getValue(setting.key) - setting.min
                contentDescription = setting.title
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        values[setting.key] = progress + setting.min
                        label.text = "${setting.title}：${progress + setting.min}${setting.unit}"
                        preview.invalidate()
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar) {}
                })
            }
            sliders[setting.key] = slider
            controls.addView(slider, LinearLayout.LayoutParams(-1, context.dp(48)))
        }
        container.addView(ScrollView(context).apply { addView(controls) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val previewLabel = TextView(context).apply { text = "当前布局按比例预览（下方固定显示）"; gravity = Gravity.CENTER; textSize = 13f }
        container.addView(previewLabel)
        container.addView(preview, LinearLayout.LayoutParams(-1, min(context.dp(330), (context.resources.displayMetrics.heightPixels * 0.36f).toInt())))
        val dialog = AlertDialog.Builder(context).setTitle("布局与实时预览").setView(container)
            .setNegativeButton("取消", null)
            .setNeutralButton("恢复默认", null)
            .setPositiveButton("保存") { _, _ ->
                OfflinePrefs.shared.edit().apply { values.forEach { (key, value) -> putInt(key, value) } }.apply()
            }.create()
        dialog.setOnShowListener {
            dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, (context.resources.displayMetrics.heightPixels * 0.92f).toInt())
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                settings.forEach { setting -> sliders.getValue(setting.key).progress = setting.default - setting.min }
                preview.invalidate()
            }
        }
        dialog.show()
    }

    private class LayoutPreview(context: Context, private val values: Map<String, Int>) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val initialHeight = OfflinePrefs.height
        private val initialWidth = OfflinePrefs.width
        private val theme = runCatching { ThemeManager.activeTheme }.getOrNull()
        private val keyboard = theme?.let { t ->
            runCatching {
                var config = t.presetKeyboards["default"] ?: t.presetKeyboards["qwerty"]
                repeat(4) { config?.importPreset?.takeIf { it.isNotEmpty() }?.let { name -> config = t.presetKeyboards[name] } }
                Keyboard(t, config)
            }.getOrNull()
        }
        private val screenWidth = resources.displayMetrics.widthPixels.toFloat()
        private val baseHeight = (keyboard?.height?.takeIf { it > 0 } ?: context.dp(240)) * 100f / initialHeight
        private val sample = "xian'an"
        private var caret = 2
        private var expanded = true
        private var rowTop = 0f
        private var rowHeight = 0f
        private var scale = 1f
        private var originX = 0f
        private var editLeft = 0f
        private var editWidth = 0f
        private fun value(key: String) = values.getValue(key)
        private fun color(key: String, fallback: Int) = runCatching { ColorManager.getColor(key) }.getOrDefault(fallback)
        private fun rect(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int, round: Float = 0f) {
            paint.color = color
            canvas.drawRoundRect(RectF(x, y, x + w, y + h), round, round, paint)
        }
        private fun label(canvas: Canvas, text: String, x: Float, y: Float, size: Float, color: Int) {
            paint.color = color; paint.textSize = size
            canvas.drawText(text, x, y - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2, paint)
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val density = resources.displayMetrics.density
            val sp = resources.displayMetrics.scaledDensity
            // A fixed scale across slider values makes changes in height visible.
            scale = min(width / screenWidth, height / (baseHeight * 1.6f + context.dp(316)))
            originX = (width - screenWidth * scale) / 2f
            val keyHeight = baseHeight * value("offline_height") / 100f
            val pinyinHeight = context.dp(if (expanded) value("offline_pinyin_height") else 40).toFloat()
            val candidateHeight = context.dp(value("offline_candidate_height")).toFloat()
            val bottom = context.dp(value("offline_bottom")).toFloat()
            val total = keyHeight + pinyinHeight + candidateHeight + bottom
            rowTop = height / scale - total
            rowHeight = pinyinHeight
            val padding = context.dp(theme?.generalStyle?.keyboardPadding ?: 0).toFloat()
            val available = screenWidth - padding * 2
            editWidth = available * value("offline_width") / 100f
            editLeft = padding + (available - editWidth) * value("offline_position") / 100f
            canvas.save()
            canvas.translate(originX, 0f)
            canvas.scale(scale, scale)
            rect(canvas, 0f, rowTop, screenWidth, total, color("back_color", Color.LTGRAY))
            rect(canvas, 0f, rowTop, screenWidth, pinyinHeight, color("text_back_color", Color.WHITE))
            val editSize = (if (expanded) value("offline_pinyin_text") else 20) * sp
            canvas.save()
            canvas.clipRect(editLeft, rowTop, editLeft + editWidth - if (expanded) context.dp(96) else 0, rowTop + pinyinHeight)
            label(canvas, sample.substring(0, caret) + "│" + sample.substring(caret), editLeft + context.dp(12), rowTop + pinyinHeight / 2, editSize, color("text_color", Color.BLACK))
            canvas.restore()
            if (expanded) {
                label(canvas, "‹", editLeft + editWidth - context.dp(84), rowTop + pinyinHeight / 2, 26 * sp, Color.DKGRAY)
                label(canvas, "›", editLeft + editWidth - context.dp(36), rowTop + pinyinHeight / 2, 26 * sp, Color.DKGRAY)
            }
            val candidateTop = rowTop + pinyinHeight
            label(canvas, "西安   先   现   仙", editLeft + context.dp(12), candidateTop + candidateHeight / 2, (theme?.generalStyle?.candidateTextSize ?: 22f) * sp, color("candidate_text_color", Color.BLACK))
            label(canvas, "⌄", editLeft + editWidth - context.dp(36), candidateTop + candidateHeight / 2, 26 * sp, Color.DKGRAY)
            val keyTop = candidateTop + candidateHeight
            val baseWidth = keyboard?.keys?.maxOfOrNull { it.x + it.width }?.toFloat()?.takeIf { it > 0 } ?: (available * initialWidth / 100f)
            val sx = editWidth / baseWidth
            val sy = keyHeight / (keyboard?.height?.takeIf { it > 0 } ?: context.dp(240))
            keyboard?.keys?.forEach { key ->
                val x = editLeft + key.x * sx
                val y = keyTop + key.y * sy
                rect(canvas, x, y, key.width * sx, key.height * sy, color("key_back_color", Color.WHITE), 6 * density)
                val text = key.getLabel()
                val size = (key.keyTextSize.takeIf { it > 0 } ?: 22f) * sp * value("offline_text") / 100f
                paint.textSize = size
                label(canvas, text, x + (key.width * sx - paint.measureText(text)) / 2, y + key.height * sy / 2, size, color("key_text_color", Color.BLACK))
            }
            canvas.restore()
        }
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val y = event.y / scale
                val x = (event.x - originX) / scale
                if (y in rowTop..(rowTop + rowHeight)) {
                    when {
                        expanded && x > editLeft + editWidth - context.dp(48) -> caret = (caret + 1).coerceAtMost(sample.length)
                        expanded && x > editLeft + editWidth - context.dp(96) -> caret = (caret - 1).coerceAtLeast(0)
                        else -> expanded = !expanded
                    }
                    invalidate()
                }
                performClick()
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }
}

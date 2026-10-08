package com.osfans.trime.ime.composition

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.osfans.trime.core.ContextProto
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.offline.OfflinePrefs
import splitties.dimensions.dp
import kotlin.math.abs

/** A normal child of the IME, including its touchable insets; no floating touch window. */
class PinyinEditorUi(context: Context) {
    var onPosition: (String, Int) -> Unit = { _, _ -> }
    var onStep: (String, Int) -> Unit = { _, _ -> }
    private var input = ""
    private var caret = 0
    private var expanded = false
    private val scroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
    private val text = object : TextView(context) {
        private var downX = 0f
        private var downY = 0f
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; return true }
                MotionEvent.ACTION_UP -> {
                    val slop = ViewConfiguration.get(context).scaledTouchSlop
                    if (abs(event.x - downX) <= slop && abs(event.y - downY) <= slop) {
                        if (!expanded) {
                            expanded = true
                            applySize()
                        } else {
                            val displayOffset = getOffsetForPosition(event.x, event.y)
                            onPosition(input, (displayOffset - if (displayOffset > caret) 1 else 0).coerceIn(0, input.length))
                        }
                        performClick()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }.apply {
        gravity = Gravity.CENTER_VERTICAL
        setSingleLine(true)
        setTextColor(ColorManager.getColor("text_color"))
        setPadding(dp(12), 0, dp(12), 0)
        contentDescription = "拼音编辑栏；点击放大，再点字母移动光标"
    }
    private fun arrow(label: String, description: String, step: Int) = Button(context).apply {
        this.text = label
        textSize = 26f
        minWidth = 0
        minimumWidth = 0
        setPadding(0, 0, 0, 0)
        contentDescription = description
        setOnClickListener { onStep(input, step) }
    }
    private val left = arrow("‹", "拼音光标左移一位", -1)
    private val right = arrow("›", "拼音光标右移一位", 1)
    val root = LinearLayout(context).apply {
        id = View.generateViewId()
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(ColorManager.getColor("text_back_color"))
        // Consume blank areas within the full-width edit row too.
        isClickable = true
        scroll.addView(text, android.widget.FrameLayout.LayoutParams(-2, -1))
        addView(scroll, LinearLayout.LayoutParams(0, dp(40), 1f))
        addView(left, LinearLayout.LayoutParams(dp(48), dp(40)))
        addView(right, LinearLayout.LayoutParams(dp(48), dp(40)))
        visibility = View.GONE
    }
    private fun applySize() {
        val height = root.context.dp(if (expanded) OfflinePrefs.pinyinHeight else 40)
        text.textSize = if (expanded) OfflinePrefs.pinyinText.toFloat() else 20f
        listOf(scroll, left, right).forEach { child ->
            child.layoutParams = child.layoutParams.apply { this.height = height }
        }
        left.visibility = if (expanded) View.VISIBLE else View.GONE
        right.visibility = if (expanded) View.VISIBLE else View.GONE
        root.requestLayout()
    }
    fun collapse() { expanded = false; applySize() }
    fun update(context: ContextProto) {
        input = context.input
        caret = context.caretPos.coerceIn(0, input.length)
        root.visibility = if (input.isEmpty()) View.GONE else View.VISIBLE
        if (input.isEmpty()) expanded = false
        val display = SpannableStringBuilder(input).insert(caret, "│")
        display.setSpan(ForegroundColorSpan(Color.rgb(40, 130, 220)), caret, caret + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.text = display
        left.isEnabled = caret > 0
        right.isEnabled = caret < input.length
        applySize()
        text.post {
            val layout = text.layout ?: return@post
            val x = layout.getPrimaryHorizontal(caret).toInt() + text.paddingLeft
            val target = when {
                x < scroll.scrollX + root.context.dp(16) -> x - root.context.dp(16)
                x > scroll.scrollX + scroll.width - root.context.dp(16) -> x - scroll.width + root.context.dp(16)
                else -> scroll.scrollX
            }
            scroll.scrollTo(target.coerceAtLeast(0), 0)
        }
    }
}

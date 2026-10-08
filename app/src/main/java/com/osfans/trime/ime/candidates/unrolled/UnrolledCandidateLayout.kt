package com.osfans.trime.ime.candidates.unrolled

import android.content.Context
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.R
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.Theme
import splitties.dimensions.dp

class UnrolledCandidateLayout(context: Context, theme: Theme) : LinearLayout(context) {
    val recyclerView = RecyclerView(context)
    private val syllableList = LinearLayout(context).apply { orientation = VERTICAL }
    var onSyllable: (Int) -> Unit = {}
    var onReturn: () -> Unit = {}
    init {
        id = R.id.unrolled_candidate_view
        orientation = HORIZONTAL
        setBackgroundColor(ColorManager.getColor("back_color"))
        val side = LinearLayout(context).apply { orientation = VERTICAL }
        side.addView(ScrollView(context).apply { addView(syllableList) }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        side.addView(Button(context).apply {
            text = "返回"
            setOnClickListener { onReturn() }
        }, LayoutParams(LayoutParams.MATCH_PARENT, dp(52)))
        addView(side, LayoutParams(dp(66), LayoutParams.MATCH_PARENT))
        addView(recyclerView, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
    }
    fun setSyllables(choices: List<String>) {
        syllableList.removeAllViews()
        choices.forEach { syllable ->
            syllableList.addView(Button(context).apply {
                text = syllable
                isAllCaps = false
                gravity = Gravity.CENTER
                setOnClickListener { onSyllable(syllable.length) }
            }, LayoutParams(LayoutParams.MATCH_PARENT, dp(56)))
        }
    }
    fun resetPosition() { recyclerView.scrollToPosition(0) }
}

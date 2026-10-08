/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.unrolled.window

import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.view.View
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.bar.InputBarDelegate
import com.osfans.trime.ime.bar.UnrollButtonStateMachine
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.candidates.CandidateViewHolder
import com.osfans.trime.ime.candidates.compact.CompactCandidateDelegate
import com.osfans.trime.ime.candidates.unrolled.CandidatesPagingSource
import com.osfans.trime.ime.candidates.unrolled.PagingCandidateViewAdapter
import com.osfans.trime.ime.candidates.unrolled.UnrolledCandidateLayout
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.kodein.di.instance
import splitties.dimensions.dp
import kotlin.math.max

abstract class BaseUnrolledCandidateWindow :
    BoardWindow.NoBarBoardWindow(),
    InputBroadcastReceiver {
    protected val service: TrimeInputMethodService by di.instance()
    protected val rime: RimeSession by di.instance()
    protected val theme: Theme by di.instance()
    private val bar: InputBarDelegate by di.instance()
    private val windowManager: BoardWindowManager by di.instance()
    private val compactCandidate: CompactCandidateDelegate by di.instance()

    private lateinit var lifecycleCoroutineScope: LifecycleCoroutineScope
    private lateinit var candidateLayout: UnrolledCandidateLayout

    protected val separatorDrawable by lazy {
        ShapeDrawable(RectShape()).apply {
            val spacing = theme.generalStyle.candidateSpacing
            val intrinsicSize = max(spacing, context.dp(spacing)).toInt()
            intrinsicWidth = intrinsicSize
            intrinsicHeight = intrinsicSize
            paint.color = ColorManager.getColor("candidate_separator_color")
        }
    }

    abstract fun onCreateCandidateLayout(): UnrolledCandidateLayout

    final override fun onCreateView(): View {
        candidateLayout =
            onCreateCandidateLayout().apply {
                recyclerView.apply {
                    // disable item cross-fade animation
                    itemAnimator = null
                }
            }
        candidateLayout.onSyllable = { index ->
            val context = syllableContext
            val choice = syllableChoices.getOrNull(index)
            if (context != null && choice != null && !selectingSyllable) service.lifecycleScope.launch {
                selectingSyllable = true
                syllableJob?.cancel()
                try {
                    val result = rime.runOnReady {
                        val ok = splitPinyin(context.input, context.composition.preedit, choice.caret)
                        ok to inputContext()
                    }
                    if (result.first) {
                        splitInput = result.second.input
                        nextSyllableStart = choice.caret + if (choice.caret < splitInput!!.length && splitInput!![choice.caret] == '\'') 1 else 0
                    }
                } finally {
                    selectingSyllable = false
                    refreshSyllables()
                }
            }
        }
        candidateLayout.onRestart = {
            splitInput = null
            nextSyllableStart = null
            refreshSyllables()
        }
        candidateLayout.onReturn = { windowManager.attachWindow(KeyboardWindow) }
        return candidateLayout
    }

    abstract val adapter: PagingCandidateViewAdapter
    abstract val layoutManager: RecyclerView.LayoutManager

    private var offsetJob: Job? = null

    private val candidatesPager by lazy {
        Pager(
            config = PagingConfig(
                pageSize = 48,
                enablePlaceholders = false,
            ),
            pagingSourceFactory = {
                CandidatesPagingSource(
                    rime,
                    total = compactCandidate.adapter.total,
                    offset = adapter.offset,
                )
            },
        )
    }

    private var candidatesSubmitJob: Job? = null

    override fun onAttached() {
        attached = true
        lifecycleCoroutineScope = candidateLayout.findViewTreeLifecycleOwner()!!.lifecycleScope
        refreshSyllables()
        bar.unrollButtonStateMachine.push(UnrollButtonStateMachine.TransitionEvent.UnrolledCandidatesAttached)
        offsetJob =
            lifecycleCoroutineScope.launch {
                compactCandidate.unrolledCandidateOffset.collect {
                    if (it <= 0) {
                        windowManager.attachWindow(KeyboardWindow)
                    } else {
                        candidateLayout.resetPosition()
                        adapter.refreshWith(
                            offset = 0,
                            highlightedIndex = compactCandidate.adapter.highlightedIdx,
                        )
                    }
                }
            }
        candidatesSubmitJob =
            lifecycleCoroutineScope.launch {
                candidatesPager.flow.collectLatest {
                    adapter.submitData(it)
                }
            }
    }

    fun bindCandidateUiViewHolder(holder: CandidateViewHolder) {
        holder.itemView.run {
            setOnClickListener { _ ->
                rime.launchOnReady { it.selectCandidate(holder.idx, global = true) }
            }
            setOnLongClickListener { view ->
                compactCandidate.showCandidateAction(holder.idx, holder.text, view)
                true
            }
        }
    }

    private var syllableJob: Job? = null
    private var syllableContext: com.osfans.trime.core.ContextProto? = null
    private var syllableChoices = emptyList<com.osfans.trime.ime.candidates.unrolled.PinyinChoices.Choice>()
    private var attached = false
    private var selectingSyllable = false
    private var splitInput: String? = null
    private var nextSyllableStart: Int? = null
    private val syllables = service.lifecycleScope.async(Dispatchers.IO) {
        service.assets.open("pinyin-syllables.txt").bufferedReader().use { it.readLines().toSet() }
    }
    private fun refreshSyllables() {
        if (!attached || selectingSyllable) return
        syllableJob?.cancel()
        syllableJob = lifecycleCoroutineScope.launch {
            val context = rime.runOnReady { inputContext() }
            if (context.input != splitInput) {
                splitInput = null
                nextSyllableStart = null
            }
            val choices = com.osfans.trime.ime.candidates.unrolled.PinyinChoices.from(context, syllables.await(), nextSyllableStart)
            syllableContext = context
            syllableChoices = choices
            candidateLayout.setSyllables(choices.map { it.label })
        }
    }
    override fun onCompositionUpdate(data: com.osfans.trime.core.CompositionProto) {
        // Inline-preedit mode intentionally sends an empty display composition.
        // Query the actual engine context rather than parsing this UI message.
        if (attached) refreshSyllables()
    }

    override fun onDetached() {
        attached = false
        bar.unrollButtonStateMachine.push(
            UnrollButtonStateMachine.TransitionEvent.UnrolledCandidatesDetached,
            UnrollButtonStateMachine.BooleanKey.UnrolledCandidatesEmpty to
                (compactCandidate.adapter.total == adapter.offset),
        )
        syllableJob?.cancel()
        syllableContext = null
        syllableChoices = emptyList()
        offsetJob?.cancel()
        candidatesSubmitJob?.cancel()
    }
}


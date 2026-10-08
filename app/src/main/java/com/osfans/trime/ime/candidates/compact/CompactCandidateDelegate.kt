/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.compact

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.view.View
import android.widget.PopupMenu
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import com.osfans.trime.R
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.bar.InputBarDelegate
import com.osfans.trime.ime.bar.UnrollButtonStateMachine
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.candidates.unrolled.decoration.FlexboxVerticalDecoration
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.dependency.InputDependencyManager
import com.osfans.trime.ime.keyboard.InputFeedbackManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.recyclerview.recyclerView
import kotlin.math.max

class CompactCandidateDelegate : InputBroadcastReceiver {
    private val di = InputDependencyManager.getInstance().di
    private val context: Context by di.instance()
    val service: TrimeInputMethodService by di.instance()
    val rime: RimeSession by di.instance()
    val theme: Theme by di.instance()
    val bar: InputBarDelegate by di.instance()
    private var loaded = emptyList<com.osfans.trime.core.CandidateItem>()
    private var revision = 0
    private var loading = false
    private var exhausted = false
    private var loadJob: kotlinx.coroutines.Job? = null
    private val _unrolledCandidateOffset = MutableSharedFlow<Int>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val unrolledCandidateOffset = _unrolledCandidateOffset.asSharedFlow()

    fun refreshUnrolled(childCount: Int) {
        val empty = loaded.isEmpty()
        _unrolledCandidateOffset.tryEmit(if (empty) 0 else 1)
        bar.unrollButtonStateMachine.push(
            UnrollButtonStateMachine.TransitionEvent.UnrolledCandidatesUpdated,
            UnrollButtonStateMachine.BooleanKey.UnrolledCandidatesEmpty to empty,
        )
    }

    val adapter by lazy {
        CompactCandidateViewAdapter(theme).apply {
            setOnItemClickListener { _, _, position ->
                rime.launchOnReady { it.selectCandidate(position, global = true) }
            }
            setOnItemLongClickListener { _, view, position ->
                showCandidateAction(position, items[position].text, view)
                true
            }
        }
    }
    fun updateLayoutParams(minWidth: Int, flexGrow: Float) {}
    val layoutManager by lazy {
        androidx.recyclerview.widget.LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
    }
    val view by lazy {
        context.recyclerView(R.id.candidate_view) {
            itemAnimator = null
            adapter = this@CompactCandidateDelegate.adapter
            layoutManager = this@CompactCandidateDelegate.layoutManager
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (this@CompactCandidateDelegate.layoutManager.findLastVisibleItemPosition() >=
                        this@CompactCandidateDelegate.adapter.itemCount - 8) loadMore()
                }
            })
        }
    }
    private fun loadMore() {
        if (loading || exhausted) return
        loading = true
        val generation = revision
        val start = loaded.size
        loadJob = service.lifecycleScope.launch {
            try {
                val more = rime.runOnReady { getCandidates(start, 48) }
                if (generation != revision) return@launch
                exhausted = more.size < 48
                loaded = loaded + more.toList()
                adapter.updateCandidates(loaded.toTypedArray(), adapter.total, adapter.highlightedIdx)
            } finally {
                if (generation == revision) loading = false
            }
        }
    }
    override fun onCandidateListUpdate(data: RimeMessage.CandidateListMessage.Data) {
        revision++
        loadJob?.cancel()
        loading = false
        val (total, highlighted, candidates) = data
        loaded = candidates.toList()
        exhausted = candidates.isEmpty()
        adapter.updateCandidates(candidates, total, highlighted)
        view.scrollToPosition(0)
        refreshUnrolled(candidates.size)
        if (!exhausted) view.post { loadMore() }
    }

    private var candidateActionMenu: PopupMenu? = null

    fun showCandidateAction(
        idx: Int,
        text: String,
        view: View,
    ) {
        candidateActionMenu?.dismiss()
        candidateActionMenu = null
        service.lifecycleScope.launch {
            InputFeedbackManager.keyPressVibrate(view, longPress = true)
            candidateActionMenu =
                PopupMenu(context, view).apply {
                    menu
                        .add(
                            buildSpannedString {
                                bold {
                                    color(ColorManager.getColor("hilited_candidate_text_color")) { append(text) }
                                }
                            },
                        ).apply {
                            isEnabled = false
                        }
                    menu.add(R.string.forget_this_word).setOnMenuItemClickListener {
                        rime.runIfReady { deleteCandidate(idx, global = true) }
                        true
                    }
                    setOnDismissListener {
                        candidateActionMenu = null
                    }
                    show()
                }
        }
    }
}

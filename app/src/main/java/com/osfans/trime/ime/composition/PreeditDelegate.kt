package com.osfans.trime.ime.composition

import android.content.Context
import android.view.inputmethod.EditorInfo
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.dependency.InputDependencyManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import org.kodein.di.instance

class PreeditDelegate : InputBroadcastReceiver {
    private val context: Context by InputDependencyManager.getInstance().di.instance()
    private val rime: RimeSession by InputDependencyManager.getInstance().di.instance()
    private val service: TrimeInputMethodService by InputDependencyManager.getInstance().di.instance()
    val ui = PinyinEditorUi(context).apply {
        onPosition = { input, pos -> rime.launchOnReady { it.movePinyinCursor(input, position = pos) } }
        onStep = { input, step -> rime.launchOnReady { it.movePinyinCursor(input, delta = step) } }
    }
    private var job: Job? = null
    override fun onStartInput(info: EditorInfo) { ui.collapse() }
    override fun onCompositionUpdate(data: CompositionProto) {
        // Inline display messages may be empty even while the engine still has input.
        job?.cancel()
        job = service.lifecycleScope.launch { ui.update(rime.runOnReady { inputContext() }) }
    }
}

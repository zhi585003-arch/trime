/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.db

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.annotation.Keep
import androidx.room.Room
import androidx.room.withTransaction
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.util.WeakHashSet
import com.osfans.trime.util.matchesAny
import com.osfans.trime.util.removeRegexSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.systemservices.clipboardManager
import timber.log.Timber

object ClipboardHelper :
    ClipboardManager.OnPrimaryClipChangedListener,
    CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default) {
    private lateinit var clbDb: Database
    private lateinit var clbDao: DatabaseDao

    fun interface OnClipboardUpdateListener {
        fun onUpdate(bean: DatabaseBean)
    }

    private val mutex = Mutex()

    var itemCount: Int = 0
        private set

    private suspend fun updateItemCount() {
        itemCount = clbDao.itemCount()
    }

    private val onUpdateListeners = WeakHashSet<OnClipboardUpdateListener>()

    fun addOnUpdateListener(listener: OnClipboardUpdateListener) {
        onUpdateListeners.add(listener)
    }

    fun removeOnUpdateListener(listener: OnClipboardUpdateListener) {
        onUpdateListeners.remove(listener)
    }

    private val clipPref = AppPrefs.defaultInstance().clipboard

    private val enabledPref = clipPref.clipboardListening

    @Keep
    private val enabledListener = PreferenceDelegate.OnChangeListener<Boolean> { _, value ->
        if (value) {
            clipboardManager.addPrimaryClipChangedListener(this)
        } else {
            clipboardManager.removePrimaryClipChangedListener(this)
        }
    }

    private val compareRules: Set<Regex> by lazy {
        val rules by clipPref.clipboardCompareRules
        rules
            .split('\n')
            .map { Regex(it.trim()) }
            .toSet()
    }

    private val outputRules: Set<Regex> by lazy {
        val rules by clipPref.clipboardOutputRules
        rules
            .split('\n')
            .map { Regex(it) }
            .toSet()
    }

    var pendingPreviewId: Int? = null
    var lastBean: DatabaseBean? = null

    private fun updateLastBean(bean: DatabaseBean) {
        lastBean = bean
        onUpdateListeners.forEach { it.onUpdate(bean) }
    }

    fun init(context: Context) {
        clipboardManager.addPrimaryClipChangedListener(this)
        clbDb =
            Room
                .databaseBuilder(context, Database::class.java, "clipboard.db")
                .addMigrations(Database.MIGRATION_3_4)
                .build()
        clbDao = clbDb.databaseDao()
        enabledListener.onChange(enabledPref.key, enabledPref.getValue())
        enabledPref.registerOnChangeListener(enabledListener)
        launch { clearExpired() }
    }

    // All clipboard mutations share a lock; collection.db keeps its original behavior.
    private const val RETENTION_MILLIS = 24L * 60 * 60 * 1000

    private suspend fun removeExpiredLocked(now: Long = System.currentTimeMillis()) {
        clbDao.deleteExpired(now - RETENTION_MILLIS)
        if (lastBean?.let { it.time <= now - RETENTION_MILLIS } == true) {
            lastBean = null
            pendingPreviewId = null
        }
        updateItemCount()
    }

    suspend fun clearExpired() = mutex.withLock { removeExpiredLocked() }

    suspend fun get(id: Int): DatabaseBean? = mutex.withLock {
        removeExpiredLocked()
        clbDao.get(id)
    }

    suspend fun preview(id: Int): Boolean = mutex.withLock {
        removeExpiredLocked()
        val bean = clbDao.get(id) ?: return@withLock false
        pendingPreviewId = id
        updateLastBean(bean)
        true
    }

    fun allBeans() = clbDao.clipboardBeans()

    // Read the current row, not a possibly expired/stale UI snapshot. Only a successful
    // paste renews retention. Copy notifications never update an existing row's time.
    suspend fun paste(id: Int, commit: (String) -> Boolean): Boolean = mutex.withLock {
        removeExpiredLocked()
        val bean = clbDao.get(id) ?: return@withLock false
        val text = bean.text ?: return@withLock false
        if (!withContext(Dispatchers.Main.immediate) { commit(text) }) return@withLock false
        val now = System.currentTimeMillis()
        clbDao.updateTime(id, now)
        pendingPreviewId = null
        lastBean = bean.copy(time = now)
        true
    }

    suspend fun updateText(id: Int, text: String) = mutex.withLock {
        removeExpiredLocked()
        val bean = clbDao.get(id) ?: return@withLock
        val duplicate = clbDao.find(text)
        if (duplicate != null && duplicate.id != id) {
            clbDao.delete(id)
            if (lastBean?.id == id) lastBean = duplicate
        } else {
            clbDao.updateText(id, text)
            if (lastBean?.id == id) lastBean = bean.copy(text = text)
        }
        updateItemCount()
    }

    suspend fun delete(id: Int) = mutex.withLock {
        removeExpiredLocked()
        clbDao.delete(id)
        if (lastBean?.id == id) lastBean = null
        updateItemCount()
    }

    suspend fun deleteAll() = mutex.withLock {
        clbDao.deleteAll()
        lastBean = null
        pendingPreviewId = null
        updateItemCount()
    }

    private var lastClipTimestamp = -1L
    private var lastClipHash = 0

    /**
     * 此方法设置监听剪贴板变化，如有新的剪贴内容，就启动选定的剪贴板管理器
     *
     * - [compareRules] 比较规则。每次通知剪贴板管理器，都会保存 ClipBoardCompare 处理过的 string。
     * 如果两次处理过的内容不变，则不通知。
     *
     * - [outputRules] 输出规则。如果剪贴板内容与规则匹配，则不通知剪贴板管理器。
     */
    override fun onPrimaryClipChanged() {
        val clip = clipboardManager.primaryClip ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timestamp = clip.description.timestamp
            if (timestamp == lastClipTimestamp) return
            lastClipTimestamp = timestamp
        } else {
            val timestamp = System.currentTimeMillis()
            val hash = clip.hashCode()
            if (timestamp - lastClipTimestamp < 100L && hash == lastClipHash) return
            lastClipTimestamp = timestamp
            lastClipHash = hash
        }
        launch {
            mutex.withLock {
                removeExpiredLocked()
                val bean = DatabaseBean.fromClipData(clip) ?: return@withLock
                if (bean.text.isNullOrBlank()) return@withLock
                if (bean.text.matchesAny(outputRules) ||
                    bean.text.removeRegexSet(compareRules).isEmpty()
                ) {
                    return@withLock
                }
                try {
                    clbDao.find(bean.text)?.let {
                        updateLastBean(it)
                        return@withLock
                    }
                    val insertedBean =
                        clbDb.withTransaction {
                            val rowId = clbDao.insert(bean)
                            removeExpiredLocked()
                            updateItemCount()
                            clbDao.get(rowId) ?: bean
                        }
                    updateLastBean(insertedBean)
                    updateItemCount()
                } catch (exception: Exception) {
                    Timber.w("Failed to update clipboard database: $exception")
                    // Do not expose an unsaved row as a pasteable history entry.
                }
            }
        }
    }
}

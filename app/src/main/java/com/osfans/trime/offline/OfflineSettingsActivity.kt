package com.osfans.trime.offline

import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.db.ClipboardHelper
import com.osfans.trime.data.db.CollectionHelper
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ThemeFilesManager
import com.osfans.trime.ui.main.settings.KeyboardSettingsFragment
import com.charleskorn.kaml.yamlMap
import com.charleskorn.kaml.yamlScalar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class OfflineSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "离线定制设置"
        if (savedInstanceState == null) supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, OfflineSettingsFragment()).commit()
    }
}

class OfflineSettingsFragment : PreferenceFragmentCompat() {
    private var exportFile: File? = null
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val file = exportFile
        if (uri != null && file != null) lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { requireContext().contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } } }
            }.onSuccess { toast("已导出") }.onFailure { toast(it.message.orEmpty()) }
        }
    }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            runCatching {
                val cr = requireContext().contentResolver
                val name = cr.query(uri, null, null, null, null)?.use {
                    it.moveToFirst(); it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                } ?: error("无法读取文件名")
                require(File(name).name == name && (name.endsWith(".custom.yaml") || name.endsWith(".trime.yaml"))) {
                    "这里导入 .custom.yaml 或 .trime.yaml；词库请用用户词典页面导入"
                }
                val bytes = withContext(Dispatchers.IO) { cr.openInputStream(uri)!!.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        require(out.size() + n <= 2 * 1024 * 1024) { "配置文件过大" }
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                } }
                require(bytes.size <= 2 * 1024 * 1024) { "配置文件过大" }
                val node = ThemeFilesManager.yaml.parseToYamlNode(bytes.toString(Charsets.UTF_8)).yamlMap
                if (name.endsWith(".trime.yaml")) {
                    val keys = node.entries.keys.map { it.yamlScalar.content }
                    require("style" in keys && "preset_keyboards" in keys) { "主题缺少 style 或 preset_keyboards" }
                }
                withContext(Dispatchers.IO) {
                    File(PrivateData.metadata, "pending-config").writeBytes(bytes)
                    File(PrivateData.metadata, "pending-name").writeText(name)
                }
                RimeDaemon.restartRime(true)
                toast("已校验并提交部署；原配置已保留，可恢复")
            }.onFailure { toast(it.message.orEmpty()) }
        }
    }
    private fun toast(text: String) { Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show() }
    private fun category(name: String): PreferenceCategory = PreferenceCategory(requireContext()).also {
        it.title = name; preferenceScreen.addPreference(it)
    }
    private fun action(group: PreferenceCategory, name: String, summary: String = "", click: () -> Unit) {
        group.addPreference(Preference(requireContext()).apply {
            title = name; this.summary = summary
            setOnPreferenceClickListener { click(); true }
        })
    }
    private fun confirm(message: String, action: () -> Unit) {
        AlertDialog.Builder(requireContext()).setMessage(message).setNegativeButton("取消", null)
            .setPositiveButton("确认") { _, _ -> action() }.show()
    }
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        val layout = category("键盘布局（收起再打开键盘生效）")
        fun slider(key: String, title: String, min: Int, max: Int, default: Int) {
            layout.addPreference(SeekBarPreference(requireContext()).apply {
                this.key = key; this.title = title; this.min = min; this.max = max
                setDefaultValue(default); showSeekBarValue = true
            })
        }
        slider("offline_height", "高度百分比", 70, 160, 100)
        slider("offline_width", "宽度百分比", 65, 100, 100)
        slider("offline_position", "左右位置：0 最左 / 100 最右", 0, 100, 50)
        slider("offline_bottom", "底部留白（dp）", 0, 100, 0)
        slider("offline_text", "按键文字百分比", 70, 150, 100)
        action(layout, "恢复布局默认值") {
            OfflinePrefs.shared.edit().apply {
                listOf("offline_height", "offline_width", "offline_position", "offline_bottom", "offline_text").forEach { remove(it) }
            }.apply()
            onCreatePreferences(null, null)
        }
        val feedback = category("按键反馈")
        feedback.addPreference(SwitchPreferenceCompat(requireContext()).apply {
            key = AppPrefs.Keyboard.VIBRATE_ON_KEY_PRESS; title = "按键振动"; setDefaultValue(true)
        })
        feedback.addPreference(SeekBarPreference(requireContext()).apply {
            key = AppPrefs.Keyboard.VIBRATION_DURATION; title = "振动时长（毫秒，0 跟随系统）"
            min = 0; max = 50; setDefaultValue(15); showSeekBarValue = true
        })
        feedback.addPreference(SeekBarPreference(requireContext()).apply {
            key = AppPrefs.Keyboard.VIBRATION_AMPLITUDE; title = "振动强度（硬件支持时有效，0 为默认）"
            min = 0; max = 255; setDefaultValue(0); showSeekBarValue = true
        })
        val privacy = category("隐私与本地数据")
        privacy.addPreference(SwitchPreferenceCompat(requireContext()).apply {
            key = "offline_learning"; title = "个人词库学习"; summary = "关闭后暂停学习，不删除已学词条"; setDefaultValue(true)
            setOnPreferenceChangeListener { _, value ->
                RimeDaemon.getFirstSessionOrNull()?.runIfReady { setRuntimeOption("_no_learning", !(value as Boolean)) }; true
            }
        })
        privacy.addPreference(SwitchPreferenceCompat(requireContext()).apply {
            title = "记录剪贴板"; isPersistent = false
            isChecked = AppPrefs.defaultInstance().clipboard.clipboardListening.getValue()
            setOnPreferenceChangeListener { _, value ->
                AppPrefs.defaultInstance().clipboard.clipboardListening.setValue(value as Boolean); true
            }
        })
        action(privacy, "清空剪贴板历史") { confirm("删除所有剪贴板历史？收藏不受影响。") { lifecycleScope.launch { ClipboardHelper.deleteAll(); toast("已清空") } } }
        action(privacy, "清空收藏") { confirm("删除所有收藏？") { lifecycleScope.launch { CollectionHelper.deleteAll(false); toast("已清空") } } }
        action(privacy, "清空个人学习词库", "不删除你主动导出的备份") {
            confirm("清除已学词条并重启输入引擎？请先备份需要保留的词库。") {
                OfflinePrefs.shared.edit().putBoolean("offline_clear_learning", true).commit()
                RimeDaemon.restartRime(true)
            }
        }
        action(privacy, "私有存储状态", "词库与配置在应用私有目录；系统自动备份和文件浏览入口已关闭") {
            val marker = File(PrivateData.metadata, "migrated").exists()
            toast(if (marker) "私有目录迁移已完成" else "首次引擎启动时迁移；旧数据暂时保留")
        }
        action(privacy, "删除迁移后留在共享目录的旧副本", "先确认新版本词库正常；旧副本在删除前仍可被有权限的应用读取") {
            val file = File(PrivateData.metadata, "old-source")
            if (!file.exists()) toast("没有待清理的旧目录") else {
                val old = File(file.readText())
                confirm("删除旧目录 ${old.absolutePath}？此操作不可恢复，请确认需要的备份另存。") {
                    lifecycleScope.launch {
                        val ok = withContext(Dispatchers.IO) { PrivateData.deleteOldCopies() }
                        if (ok) file.delete()
                        toast(if (ok) "旧副本已删除" else "删除失败，请检查旧目录权限")
                    }
                }
            }
        }
        val files = category("高级配置与备份")
        action(files, "导入配置或主题", "校验 YAML，保留旧文件，然后部署") { importLauncher.launch(arrayOf("*/*")) }
        action(files, "导出配置或用户词典备份", "先在用户词典页面执行备份；此处可导出 sync 目录中的快照") {
            val choices = PrivateData.userDir.walkTopDown().onEnter { it.name != "build" && !it.name.endsWith(".userdb") }
                .filter { it.isFile && (it.name.endsWith(".yaml") || it.name.endsWith(".userdb.txt")) }.toList()
            if (choices.isEmpty()) toast("暂无文件") else AlertDialog.Builder(requireContext()).setItems(
                choices.map { it.relativeTo(PrivateData.userDir).path }.toTypedArray(),
            ) { _, index -> exportFile = choices[index]; export.launch(choices[index].name) }.show()
        }
        action(files, "恢复上次导入前的配置") {
            val name = File(PrivateData.metadata, "last-name")
            val backup = File(PrivateData.metadata, "last-config")
            if (!name.exists() || !backup.exists()) toast("暂无旧配置备份") else confirm("恢复上次导入前的配置并重新部署？") {
                backup.copyTo(File(PrivateData.metadata, "pending-config"), overwrite = true)
                name.copyTo(File(PrivateData.metadata, "pending-name"), overwrite = true)
                RimeDaemon.restartRime(true)
            }
        }
    }
}

package com.osfans.trime.offline

import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.util.appContext
import java.io.File
import java.security.MessageDigest

object PrivateData {
    val userDir get() = File(appContext.filesDir, "rime-private/user").also { it.mkdirs() }
    val sharedDir get() = File(appContext.filesDir, "rime-private/shared").also { it.mkdirs() }
    val metadata get() = File(appContext.filesDir, "offline-metadata").also { it.mkdirs() }
    private fun digest(file: File): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
        }
        return md.digest()
    }
    fun deleteOldCopies(): Boolean {
        val record = File(metadata, "old-source")
        val listing = File(metadata, "copied-files")
        if (!record.exists() || !listing.exists()) return false
        val source = File(record.readText()).canonicalFile
        var success = true
        listing.readLines().filter { it.isNotBlank() }.forEach { entry ->
            val expected = entry.substringBefore("\t")
            val relative = entry.substringAfter("\t")
            val file = File(source, relative).canonicalFile
            check(file.path.startsWith(source.path + File.separator))
            if (file.exists()) {
                val current = digest(file).joinToString("") { "%02x".format(it) }
                if (current != expected || !file.delete()) success = false
            }
        }
        // Do not delete newly added files or recursively delete the source directory.
        if (success) { record.delete(); listing.delete() }
        return success
    }
    // Called before librime opens any user database; partial copies are retried.
    fun prepare() {
        val marker = File(metadata, "migrated")
        if (!marker.exists()) {
            val source = File(AppPrefs.defaultInstance().profile.userDataDir.getValue())
            if (source.canonicalFile != userDir.canonicalFile && source.isDirectory) {
                check(source.canRead()) { "无法读取旧词库目录，请先恢复文件访问权限" }
                val copied = mutableListOf<String>()
                source.walkTopDown().onEnter { it == source || it.name != "build" }.forEach { old ->
                    if (old.isFile) {
                        check(old.canonicalPath.startsWith(source.canonicalPath + File.separator))
                        val dest = File(userDir, old.relativeTo(source).path)
                        dest.parentFile?.mkdirs()
                        old.copyTo(dest, overwrite = true)
                        check(digest(old).contentEquals(digest(dest))) { "词库迁移校验失败，旧数据未删除" }
                        copied.add(digest(old).joinToString("") { "%02x".format(it) } + "\t" + old.relativeTo(source).path)
                    }
                }
                File(metadata, "old-source").writeText(source.absolutePath)
                File(metadata, "copied-files").writeText(copied.joinToString("\n"))
            }
            marker.writeText("complete")
        }
        val prefs = OfflinePrefs.shared
        if (prefs.getBoolean("offline_clear_learning", false)) {
            userDir.listFiles()?.filter { it.name.endsWith(".userdb") }?.forEach {
                check(it.deleteRecursively()) { "无法清除用户词库" }
            }
            prefs.edit().putBoolean("offline_clear_learning", false).commit()
        }
        val removal = File(metadata, "pending-remove")
        if (removal.exists()) {
            val name = removal.readText()
            check(File(name).name == name && name.isNotBlank())
            val target = File(userDir, name)
            check(!target.exists() || target.delete())
            removal.delete()
        }
        val staged = File(metadata, "pending-config")
        val nameFile = File(metadata, "pending-name")
        if (staged.exists() && nameFile.exists()) {
            val name = nameFile.readText()
            check(File(name).name == name && name.isNotBlank())
            val dest = File(userDir, name)
            val backup = File(metadata, "last-config")
            if (dest.exists()) dest.copyTo(backup, overwrite = true) else backup.delete()
            File(metadata, "last-name").writeText(name)
            staged.copyTo(dest, overwrite = true)
            staged.delete()
            nameFile.delete()
        }
    }
}

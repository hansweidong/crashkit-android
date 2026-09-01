package com.yj.crashkit.internal

import com.yj.crashkit.CrashRecord
import com.yj.crashkit.util.KitLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset

/** 先落盘再上报，供后续 HTTP reporter 补传。会按数量、年龄和目录体积淘汰旧文件。 */
object PendingStore {
    private const val TAG = "PendingStore"

    @JvmStatic
    fun save(dumpDir: File?, record: CrashRecord?) {
        if (dumpDir == null || record == null) {
            return
        }
        val pending = File(dumpDir, "pending")
        if (!pending.exists() && !pending.mkdirs()) {
            KitLog.e(TAG, "mkdir pending failed")
            return
        }
        val out = File(pending, "${record.crashId}.json")
        try {
            val json = JSONObject()
            json.put("crashId", record.crashId)
            json.put("type", record.type.wireName())
            json.put("meta", record.metaJson)
            json.put("dumps", paths(record.dumpFiles))
            json.put("logs", paths(record.logFiles))
            out.outputStream().use { fos ->
                fos.write(json.toString().toByteArray(Charset.forName("UTF-8")))
            }
        } catch (t: Throwable) {
            KitLog.e(TAG, "save pending", t)
        }
        prunePending(pending)
    }

    @JvmStatic
    fun prune(dumpDir: File?) {
        if (dumpDir == null || !dumpDir.exists()) {
            return
        }
        prunePending(File(dumpDir, "pending"))
        pruneDumpDir(dumpDir)
    }

    private fun prunePending(pending: File) {
        if (!pending.isDirectory) {
            return
        }
        val files = pending.listFiles() ?: return
        val now = System.currentTimeMillis()
        val kept = ArrayList<File>()
        for (f in files) {
            if (!f.isFile) {
                continue
            }
            if (now - f.lastModified() > CrashKitOnlinePolicy.PENDING_MAX_AGE_MS) {
                deleteQuietly(f)
            } else {
                kept.add(f)
            }
        }
        if (kept.size <= CrashKitOnlinePolicy.MAX_PENDING_FILES) {
            return
        }
        kept.sortBy { it.lastModified() }
        var extra = kept.size - CrashKitOnlinePolicy.MAX_PENDING_FILES
        for (f in kept) {
            if (extra <= 0) {
                break
            }
            if (deleteQuietly(f)) {
                extra--
            }
        }
    }

    private fun pruneDumpDir(dumpDir: File) {
        val files = dumpDir.listFiles() ?: return
        val now = System.currentTimeMillis()
        var total = 0L
        val victims = ArrayList<File>()
        for (f in files) {
            if (!f.isFile) {
                continue
            }
            val name = f.name
            val age = now - f.lastModified()
            if (name.endsWith(".hprof") && age > CrashKitOnlinePolicy.HPROF_MAX_AGE_MS) {
                deleteQuietly(f)
                continue
            }
            total += f.length()
            victims.add(f)
        }
        if (total <= CrashKitOnlinePolicy.DUMP_DIR_MAX_BYTES) {
            return
        }
        victims.sortBy { it.lastModified() }
        for (f in victims) {
            if (total <= CrashKitOnlinePolicy.DUMP_DIR_MAX_BYTES) {
                break
            }
            val len = f.length()
            if (deleteQuietly(f)) {
                total -= len
            }
        }
    }

    private fun deleteQuietly(file: File): Boolean {
        return try {
            file.delete()
        } catch (_: Throwable) {
            false
        }
    }

    private fun paths(files: List<File>?): JSONArray {
        val arr = JSONArray()
        if (files == null) {
            return arr
        }
        for (f in files) {
            arr.put(f.absolutePath)
        }
        return arr
    }
}

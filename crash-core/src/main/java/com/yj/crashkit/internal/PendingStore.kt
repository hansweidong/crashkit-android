package com.yj.crashkit.internal

import com.yj.crashkit.CrashRecord
import com.yj.crashkit.CrashType
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
        val pending = File(dumpDir, CrashFiles.PENDING_DIR)
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

    /** 三阶段全部上报成功后调用。留着的就是上次没投递成功的，下次启动重投。 */
    @JvmStatic
    fun remove(dumpDir: File?, crashId: String?) {
        if (dumpDir == null || crashId.isNullOrEmpty()) {
            return
        }
        deleteQuietly(File(File(dumpDir, CrashFiles.PENDING_DIR), "$crashId.json"))
    }

    /**
     * 读回上次进程没投递成功的记录，按时间从旧到新。
     *
     * dump / log 文件可能已经被 [prune] 按体积或年龄清掉了，这里只带还存在的那些，
     * 元信息（meta）本身在 json 里，即使附件没了也还有上报价值。
     */
    @JvmStatic
    fun loadAll(dumpDir: File?): List<CrashRecord> {
        if (dumpDir == null) {
            return emptyList()
        }
        val pending = File(dumpDir, CrashFiles.PENDING_DIR)
        if (!pending.isDirectory) {
            return emptyList()
        }
        val files = pending.listFiles() ?: return emptyList()
        val sorted = files.filter { it.isFile }.sortedBy { it.lastModified() }
        val out = ArrayList<CrashRecord>()
        for (f in sorted) {
            if (out.size >= CrashKitOnlinePolicy.MAX_PENDING_FILES) {
                break
            }
            out.add(parse(f) ?: continue)
        }
        return out
    }

    private fun parse(file: File): CrashRecord? {
        return try {
            val json = JSONObject(String(file.readBytes(), Charset.forName("UTF-8")))
            val id = json.optString("crashId")
            if (id.isEmpty()) {
                deleteQuietly(file)
                return null
            }
            val wire = json.optString("type")
            val type = CrashType.values().firstOrNull { it.wireName() == wire } ?: CrashType.JAVA_ERROR
            CrashRecord(
                id,
                type,
                json.optString("meta"),
                existing(json.optJSONArray("dumps")),
                existing(json.optJSONArray("logs")),
            )
        } catch (t: Throwable) {
            // 解析不了的永远解析不了，别让它一直占着重投的名额
            KitLog.e(TAG, "read pending ${file.name}", t)
            deleteQuietly(file)
            null
        }
    }

    private fun existing(arr: JSONArray?): List<File> {
        if (arr == null) {
            return emptyList()
        }
        val out = ArrayList<File>(arr.length())
        for (i in 0 until arr.length()) {
            val p = arr.optString(i)
            if (p.isNullOrEmpty()) {
                continue
            }
            val f = File(p)
            if (f.exists()) {
                out.add(f)
            }
        }
        return out
    }

    @JvmStatic
    fun prune(dumpDir: File?) {
        if (dumpDir == null || !dumpDir.exists()) {
            return
        }
        prunePending(File(dumpDir, CrashFiles.PENDING_DIR))
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

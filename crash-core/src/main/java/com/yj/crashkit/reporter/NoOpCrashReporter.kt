package com.yj.crashkit.reporter

import com.yj.crashkit.CrashRecord
import com.yj.crashkit.ReportStage
import com.yj.crashkit.util.KitLog

object NoOpCrashReporter : CrashReporter {
    override fun report(record: CrashRecord, stage: ReportStage, callback: CrashReporter.ReportCallback) {
        KitLog.i("NoOpCrashReporter", "skip upload ${record.type} id=${record.crashId} stage=$stage")
        callback.onResult(true)
    }
}

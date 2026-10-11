package com.trace.app.insight

import com.trace.app.model.Record
import java.text.SimpleDateFormat
import java.util.*

class InsightEngine {

    fun isNightImpulse(r: Record): Boolean = r.isNight && r.amount >= 30

    fun isNightNow(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return h >= 23 || h < 3
    }

    fun generateReport(records: List<Record>): String {
        if (records.isEmpty()) {
            return "还没有记账数据。点「记一笔」开始。"
        }
        val total = records.sumOf { it.amount }
        val night = records.filter { it.isNight }
        val nightCount = night.size
        val nightAmount = night.sumOf { it.amount }
        val nightPct = if (total > 0) nightAmount / total * 100 else 0.0
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        val sb = StringBuilder()
        sb.appendLine("📊 共 ${records.size} 笔 · ¥ %.2f".format(total))
        sb.appendLine("🌙 深夜记录 $nightCount 笔 / ¥ %.2f (%.1f%%)".format(nightAmount, nightPct))
        val last = records.first()
        sb.appendLine("⏰ 最近一笔：${fmt.format(Date(last.timestamp))} · ¥ %.2f".format(last.amount))
        if (nightPct > 30) {
            sb.appendLine("⚠️ 深夜消费占比偏高 (${"%.0f".format(nightPct)}%)，建议 23 点后锁购物 App")
        } else if (nightPct > 15) {
            sb.appendLine("💡 深夜消费有苗头，注意识别")
        } else {
            sb.appendLine("✅ 消费习惯健康")
        }
        return sb.toString()
    }
}

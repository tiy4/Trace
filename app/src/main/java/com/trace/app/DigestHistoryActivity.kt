package com.trace.app

import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.trace.app.ai.DailySummary
import com.trace.app.db.TraceDbHelper
import com.trace.app.ui.BottomNav
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.YearMonth
import java.util.Date
import java.util.Locale

/**
 * 历史总结（独立页）：日历选一天，看那天的总结。
 *
 * 数据来源就是 `daily_digest` 表：`digestsSince` 一次取回来按天分组，
 * 日历只负责「哪天有总结」（圆点/t 强调色），点某天就把那天的正文画到下面。
 *
 * 两个刻意的限制：
 *  - 过去的日子只读：自动总结是在当天生成的，事后补写会缺当天的现场数据，
 *    与其给用户一份「补出来的假历史」，不如直说这天没有；
 *  - 只有「今天」给写/重写按钮，复用首页那套 [DailySummary.generate]。
 */
class DigestHistoryActivity : AppCompatActivity() {

    private lateinit var db: TraceDbHelper
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

    private var month: YearMonth = YearMonth.now()
    private var byDay: Map<String, TraceDbHelper.Digest> = emptyMap()
    private var selectedDay: String = DailySummary.dayKey()
    private var created = false

    private val today: String get() = DailySummary.dayKey()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_digest_history)
        BottomNav.bind(this, BottomNav.HOME)
        db = TraceDbHelper(this)
        selectedDay = today

        findViewById<Button>(R.id.btnPrevMonth).setOnClickListener {
            month = month.minusMonths(1)
            buildMonth()
        }
        findViewById<Button>(R.id.btnNextMonth).setOnClickListener {
            month = month.plusMonths(1)
            buildMonth()
        }
        findViewById<Button>(R.id.btnToday).setOnClickListener {
            month = YearMonth.now()
            selectedDay = today
            buildMonth()
            renderSelected()
        }
        reload()
        created = true
    }

    override fun onResume() {
        super.onResume()
        // 从写总结那边回来时（或者别的页面写过），日历上的圆点要跟上。
        if (created) reload()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun reload() {
        byDay = db.digestsSince("1970-01-01")
            .groupBy { it.day }
            .mapValues { e -> e.value.maxByOrNull { it.createdAt } ?: e.value.first() }
        buildMonth()
        renderSelected()
    }

    // ---------------------------------------------------------------- 日历

    private fun buildMonth() {
        findViewById<TextView>(R.id.tvMonth).text = "${month.year} 年 ${month.monthValue} 月"
        val host = findViewById<LinearLayout>(R.id.calRows)
        host.removeAllViews()

        // 周一当第一列（中国习惯）：DayOfWeek.value 是 1=周一 … 7=周日。
        val lead = month.atDay(1).dayOfWeek.value - 1
        val days = month.lengthOfMonth()
        var row = newRow(host)
        var idx = 0
        repeat(lead) {
            row.addView(cell("", null))
            idx++
        }
        for (d in 1..days) {
            if (idx > 0 && idx % 7 == 0) row = newRow(host)
            row.addView(cell(d.toString(), month.atDay(d).toString()))
            idx++
        }
        while (idx % 7 != 0) {
            row.addView(cell("", null))
            idx++
        }
    }

    private fun newRow(host: LinearLayout): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52)
        )
        host.addView(row)
        return row
    }

    private fun cell(label: String, day: String?): View {
        val hasDigest = day != null && byDay.containsKey(day)
        val cell = LinearLayout(this)
        cell.orientation = LinearLayout.VERTICAL
        cell.gravity = Gravity.CENTER
        cell.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        if (day != null) {
            if (day == selectedDay) {
                cell.background = ContextCompat.getDrawable(this, R.drawable.bg_day_selected)
            }
            cell.isClickable = true
            cell.setOnClickListener {
                selectedDay = day
                month = YearMonth.from(LocalDate.parse(day))
                buildMonth()
                renderSelected()
            }
        }

        val num = TextView(this)
        num.text = label
        num.gravity = Gravity.CENTER
        num.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        num.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    day == null -> R.color.text_3
                    day == selectedDay || hasDigest -> R.color.accent
                    else -> R.color.text_2
                }
            )
        )
        if (hasDigest || day == today) num.setTypeface(num.typeface, Typeface.BOLD)
        cell.addView(num)

        val dot = View(this)
        val lp = LinearLayout.LayoutParams(dp(5), dp(5))
        lp.topMargin = dp(3)
        dot.layoutParams = lp
        dot.background = ContextCompat.getDrawable(this, R.drawable.dot_accent)
        dot.visibility = if (hasDigest) View.VISIBLE else View.INVISIBLE
        cell.addView(dot)
        return cell
    }

    // ------------------------------------------------------------ 选中那天

    private fun renderSelected() {
        val d = byDay[selectedDay]
        val title = findViewById<TextView>(R.id.tvDayTitle)
        val body = findViewById<TextView>(R.id.tvDayText)
        val meta = findViewById<TextView>(R.id.tvDayMeta)
        val btnWrite = findViewById<Button>(R.id.btnDayWrite)
        val btnDetail = findViewById<Button>(R.id.btnDayDetail)

        findViewById<TextView>(R.id.tvHistoryMeta).text =
            "共 ${byDay.size} 天有总结 · 点日历上有圆点的日期回看"

        if (d == null) {
            title.text = selectedDay
            body.text = if (selectedDay == today) "今天还没有总结。" else "这天没有总结。"
            meta.text = if (selectedDay == today) {
                "熄屏充电时会自动写一份；也可以现在点下面按钮手动写"
            } else {
                "历史日期不补写：总结依赖当天的现场数据，事后补出来的不可信"
            }
            btnDetail.visibility = View.GONE
        } else {
            title.text = "$selectedDay · " + (if (d.engine == "cloud") "云端" else "端侧")
            body.text = d.text
            meta.text = "${d.model} · 生成于 ${timeFmt.format(Date(d.createdAt))}"
            btnDetail.visibility = View.VISIBLE
            btnDetail.setOnClickListener { showStats(d) }
        }

        // 只有今天可以写（重写）——见类注释。
        btnWrite.visibility = if (selectedDay == today) View.VISIBLE else View.GONE
        btnWrite.isEnabled = true
        btnWrite.text = if (d == null) "写今天的总结" else "重写今天的总结"
        btnWrite.setOnClickListener { writeToday() }
    }

    private fun writeToday() {
        val btn = findViewById<Button>(R.id.btnDayWrite)
        btn.isEnabled = false
        btn.text = "正在写…（端侧模型独占，约 1 分钟）"
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                DailySummary.generate(this@DigestHistoryActivity, db, force = true)
            }
            if (r.ok) {
                reload()
                Toast.makeText(this@DigestHistoryActivity, "已生成并留档", Toast.LENGTH_SHORT).show()
            } else {
                btn.isEnabled = true
                btn.text = "重写今天的总结"
                Toast.makeText(this@DigestHistoryActivity, "没写成：${r.error}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showStats(d: TraceDbHelper.Digest) {
        AlertDialog.Builder(this)
            .setTitle("${d.day} 总结的输入")
            .setMessage(d.stats)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
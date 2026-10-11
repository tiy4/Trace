package com.trace.app.ui

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.trace.app.BehaviorActivity
import com.trace.app.ChatActivity
import com.trace.app.MainActivity
import com.trace.app.R
import com.trace.app.RecordsActivity

/**
 * 全局底部导航的绑定与高亮（DESIGN SPEC 03）。
 *
 * 用法：布局里 <include layout="@layout/view_bottom_nav" />，
 * Activity 的 onCreate 里 `BottomNav.bind(this, BottomNav.HOME)`。
 *
 * 跳转规则：跳到别的页面 = startActivity + finish() 自己（保持栈里只有一个页面，
 * 返回键一定回首页）；跳首页用 CLEAR_TOP|SINGLE_TOP 复用已有实例，所以 MainActivity
 * 在清单里声明了 launchMode="singleTop"。
 */
object BottomNav {
    const val HOME = "home"
    const val RECORDS = "records"
    const val BEHAVIOR = "behavior"
    const val CHAT = "chat"

    /** 带着它进 MainActivity 就直接弹「记一笔」。 */
    const val EXTRA_ADD = "trace.open_add"

    fun bind(activity: Activity, current: String) {
        item(activity, R.id.navHome, R.id.tvNavHomeIcon, R.id.tvNavHomeLabel, current == HOME) {
            if (current != HOME) {
                activity.startActivity(
                    Intent(activity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
                activity.finish()
            }
        }
        item(activity, R.id.navRecords, R.id.tvNavRecordsIcon, R.id.tvNavRecordsLabel, current == RECORDS) {
            go(activity, current, RECORDS, Intent(activity, RecordsActivity::class.java))
        }
        item(activity, R.id.navAdd, 0, R.id.tvNavAddLabel, false) {
            if (activity is MainActivity) {
                activity.openAddDialog()
            } else {
                activity.startActivity(
                    Intent(activity, MainActivity::class.java)
                        .putExtra(EXTRA_ADD, true)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
                activity.finish()
            }
        }
        item(activity, R.id.navBehavior, R.id.tvNavBehaviorIcon, R.id.tvNavBehaviorLabel, current == BEHAVIOR) {
            go(activity, current, BEHAVIOR, Intent(activity, BehaviorActivity::class.java))
        }
        item(activity, R.id.navChat, R.id.tvNavChatIcon, R.id.tvNavChatLabel, current == CHAT) {
            go(activity, current, CHAT, Intent(activity, ChatActivity::class.java))
        }
    }

    private fun go(activity: Activity, current: String, key: String, intent: Intent) {
        if (current == key) return
        activity.startActivity(intent)
        activity.finish()
    }

    private fun item(
        activity: Activity,
        rootId: Int,
        iconId: Int,
        labelId: Int,
        selected: Boolean,
        onClick: () -> Unit,
    ) {
        val root: View = activity.findViewById(rootId) ?: return
        root.setOnClickListener { onClick() }
        val on = ContextCompat.getColor(activity, R.color.accent)
        val off = ContextCompat.getColor(activity, R.color.nav_idle)
        val color = if (selected) on else off
        activity.findViewById<TextView>(labelId)?.setTextColor(color)
        if (iconId != 0) activity.findViewById<TextView>(iconId)?.setTextColor(color)
    }
}
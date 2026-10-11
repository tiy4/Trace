package com.trace.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.trace.app.db.TraceDbHelper
import com.trace.app.ui.BottomNav
import com.trace.app.model.Record
import java.text.SimpleDateFormat
import java.util.*

class RecordsActivity : AppCompatActivity() {

    private lateinit var db: TraceDbHelper
    private lateinit var rv: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_records)
        BottomNav.bind(this, BottomNav.RECORDS)
        db = TraceDbHelper(this)
        rv = findViewById(R.id.rv)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = RecordAdapter(db.allRecords())
    }
}

class RecordAdapter(private val items: List<Record>) :
    RecyclerView.Adapter<RecordAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val amount: TextView = v.findViewById(R.id.tvAmount)
        val meta: TextView = v.findViewById(R.id.tvMeta)
        val tag: TextView = v.findViewById(R.id.tvTag)
    }

    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_record, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        holder.amount.text = "-¥ %.2f".format(r.amount)
        holder.meta.text = "${r.category} · ${r.merchant ?: ""} · ${fmt.format(Date(r.timestamp))}"
        holder.tag.text = if (r.isNight) "🌙 深夜" else ""
        holder.tag.visibility = if (r.isNight) View.VISIBLE else View.GONE
    }

    override fun getItemCount(): Int = items.size
}

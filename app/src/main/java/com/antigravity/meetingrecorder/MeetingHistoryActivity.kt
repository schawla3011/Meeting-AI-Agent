package com.antigravity.meetingrecorder

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class MeetingHistoryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_meeting_history)

        findViewById<TextView>(R.id.btn_history_back).setOnClickListener { finish() }

        val rv         = findViewById<RecyclerView>(R.id.rv_meetings)
        val emptyState = findViewById<View>(R.id.empty_state)
        val tvCount    = findViewById<TextView>(R.id.tv_meeting_count)

        rv.layoutManager = LinearLayoutManager(this)

        CoroutineScope(Dispatchers.IO).launch {
            val meetings = MeetingDatabase.getInstance(applicationContext)
                .meetingDao().getAllMeetings()

            withContext(Dispatchers.Main) {
                if (meetings.isEmpty()) {
                    emptyState.visibility = View.VISIBLE
                    rv.visibility         = View.GONE
                } else {
                    emptyState.visibility = View.GONE
                    rv.visibility         = View.VISIBLE
                    tvCount.text          = "${meetings.size} meeting${if (meetings.size == 1) "" else "s"} saved"
                    rv.adapter            = MeetingAdapter(meetings) { meeting ->
                        val i = Intent(this@MeetingHistoryActivity, MeetingDetailActivity::class.java)
                        i.putExtra(MeetingDetailActivity.EXTRA_MEETING_ID, meeting.id)
                        startActivity(i)
                    }
                }
            }
        }
    }

    // ── Adapter ──────────────────────────────────────────────────────────────

    private class MeetingAdapter(
        private val items: List<Meeting>,
        private val onClick: (Meeting) -> Unit
    ) : RecyclerView.Adapter<MeetingAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvDate:    TextView = v.findViewById(R.id.tv_item_date)
            val tvSummary: TextView = v.findViewById(R.id.tv_item_summary)
            val tvTasks:   TextView = v.findViewById(R.id.tv_item_tasks)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_meeting, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val m = items[position]
            holder.tvDate.text    = m.date
            holder.tvSummary.text = m.summary.ifBlank { "No summary available" }
                .replace("•", "").trim()

            val taskCount = try { JSONArray(m.tasksJson).length() } catch (_: Exception) { 0 }
            holder.tvTasks.text = if (taskCount > 0) "✅ $taskCount action item${if (taskCount == 1) "" else "s"}"
                                  else "No tasks"

            holder.itemView.setOnClickListener { onClick(m) }
        }
    }
}

package com.antigravity.meetingrecorder

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class MeetingDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MEETING_ID = "meeting_id"
    }

    private enum class DetailTab { SUMMARY, TASKS, TRANSCRIPT }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_meeting_detail)

        findViewById<TextView>(R.id.btn_detail_back).setOnClickListener { finish() }

        val meetingId = intent.getLongExtra(EXTRA_MEETING_ID, -1L)
        if (meetingId == -1L) { finish(); return }

        CoroutineScope(Dispatchers.IO).launch {
            val meeting = MeetingDatabase.getInstance(applicationContext)
                .meetingDao().getMeetingById(meetingId)

            withContext(Dispatchers.Main) {
                if (meeting == null) { finish(); return@withContext }
                bindMeeting(meeting)
            }
        }
    }

    private fun bindMeeting(meeting: Meeting) {
        findViewById<TextView>(R.id.tv_detail_date).text     = meeting.date
        findViewById<TextView>(R.id.tv_detail_filename).text = meeting.filename

        // Summary
        findViewById<TextView>(R.id.detail_tv_summary).text =
            meeting.summary.ifBlank { "No summary available." }

        // Tasks
        val container = findViewById<LinearLayout>(R.id.detail_tasks_container)
        container.removeAllViews()
        try {
            val arr = JSONArray(meeting.tasksJson)
            for (i in 0 until arr.length()) {
                val t        = arr.getJSONObject(i)
                val task     = t.optString("task", "")
                val owner    = t.optString("owner", "Unassigned")
                val deadline = t.optString("deadline", "Not specified")
                addDetailTaskRow(container, task, owner, deadline, i == arr.length() - 1)
            }
            if (arr.length() == 0) {
                container.addView(makeText("No action items recorded.", getColor(R.color.text_secondary), 13f))
            }
        } catch (_: Exception) {
            container.addView(makeText("Could not load tasks.", getColor(R.color.text_secondary), 13f))
        }

        // Transcript
        findViewById<TextView>(R.id.detail_tv_transcript).text =
            meeting.transcript.ifBlank { "No transcript available." }

        // Wire up tab chips
        val chips = listOf(
            DetailTab.SUMMARY    to R.id.detail_chip_summary,
            DetailTab.TASKS      to R.id.detail_chip_tasks,
            DetailTab.TRANSCRIPT to R.id.detail_chip_transcript
        )
        chips.forEach { (tab, chipId) ->
            findViewById<TextView>(chipId).setOnClickListener { showDetailTab(tab) }
        }
        showDetailTab(DetailTab.SUMMARY)
    }

    private fun showDetailTab(tab: DetailTab) {
        val active   = getColor(R.color.white)
        val inactive = getColor(R.color.text_secondary)

        val summaryChip    = findViewById<TextView>(R.id.detail_chip_summary)
        val tasksChip      = findViewById<TextView>(R.id.detail_chip_tasks)
        val transcriptChip = findViewById<TextView>(R.id.detail_chip_transcript)

        summaryChip.isSelected    = (tab == DetailTab.SUMMARY)
        tasksChip.isSelected      = (tab == DetailTab.TASKS)
        transcriptChip.isSelected = (tab == DetailTab.TRANSCRIPT)

        summaryChip.setTextColor(   if (tab == DetailTab.SUMMARY)    active else inactive)
        tasksChip.setTextColor(     if (tab == DetailTab.TASKS)      active else inactive)
        transcriptChip.setTextColor(if (tab == DetailTab.TRANSCRIPT) active else inactive)

        findViewById<View>(R.id.detail_summary_card).visibility    = if (tab == DetailTab.SUMMARY)    View.VISIBLE else View.GONE
        findViewById<View>(R.id.detail_tasks_card).visibility      = if (tab == DetailTab.TASKS)      View.VISIBLE else View.GONE
        findViewById<View>(R.id.detail_transcript_card).visibility = if (tab == DetailTab.TRANSCRIPT) View.VISIBLE else View.GONE
    }

    private fun addDetailTaskRow(
        container: LinearLayout, task: String, owner: String, deadline: String, isLast: Boolean
    ) {
        val dp  = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
        }
        row.addView(TextView(this).apply {
            text = task; textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (6 * dp).toInt() }
        })
        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        meta.addView(TextView(this).apply {
            text = "👤 $owner"; textSize = 11f
            setTextColor(getColor(R.color.owner_chip_text))
            setBackgroundColor(getColor(R.color.owner_chip_bg))
            setPadding((8 * dp).toInt(), (3 * dp).toInt(), (8 * dp).toInt(), (3 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = (8 * dp).toInt() }
        })
        if (deadline.isNotBlank() && !deadline.equals("not specified", ignoreCase = true)) {
            meta.addView(TextView(this).apply {
                text = "🗓 $deadline"; textSize = 11f
                setTextColor(getColor(R.color.deadline_text))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
        }
        row.addView(meta)
        if (!isLast) {
            row.addView(View(this).apply {
                setBackgroundColor(getColor(R.color.divider))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            })
        }
        container.addView(row)
    }

    private fun makeText(text: String, color: Int, size: Float) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
}

package com.anas.deviceagent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var sendBtn: Button
    private lateinit var stopBtn: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(32), dp(12), dp(12))
            isFocusableInTouchMode = true
        }
        status = TextView(this).apply { textSize = 13f }
        val baseEt = EditText(this).apply {
            hint = "Base URL (مثال: https://api.groq.com/openai/v1)"
            setText(prefs.getString("base2", "")); setSingleLine()
        }
        val model2Et = EditText(this).apply {
            hint = "Model"
            setText(prefs.getString("model2", "")); setSingleLine()
        }
        val key2Et = EditText(this).apply {
            hint = "API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("key2", "")); setSingleLine()
        }
        val saveBtn = Button(this).apply {
            text = "حفظ"
            setOnClickListener {
                prefs.edit().putString("base2", baseEt.text.toString().trim())
                    .putString("model2", model2Et.text.toString().trim())
                    .putString("key2", key2Et.text.toString().trim()).apply()
                Toast.makeText(this@MainActivity, "تم الحفظ", Toast.LENGTH_SHORT).show()
            }
        }
        val accBtn = Button(this).apply {
            text = "تفعيل خدمة الوصول"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val row1 = LinearLayout(this).apply {
            addView(saveBtn, LinearLayout.LayoutParams(0, -2, 1f))
            addView(accBtn, LinearLayout.LayoutParams(0, -2, 2f))
        }
        logView = TextView(this).apply { textSize = 15f; textDirection = View.TEXT_DIRECTION_FIRST_STRONG }
        scroll = ScrollView(this).apply { addView(logView) }
        val input = EditText(this).apply {
            hint = "اكتب الأمر (مثال: افتح الإعدادات وطفي الواي فاي)"
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            minLines = 1; maxLines = 4
        }
        sendBtn = Button(this).apply {
            text = "نفّذ"
            setOnClickListener {
                val goal = input.text.toString().trim()
                if (goal.isEmpty()) return@setOnClickListener
                input.setText("")
                Agent.start(this@MainActivity, goal)
                refresh()
            }
        }
        stopBtn = Button(this).apply {
            text = "إيقاف"
            setOnClickListener { Agent.stop() }
        }
        val row2 = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, -2, 1f))
            addView(sendBtn); addView(stopBtn)
        }
        root.addView(status)
                root.addView(baseEt); root.addView(model2Et); root.addView(key2Et); root.addView(row1)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(row2)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        Agent.listener = { line -> runOnUiThread { append(line) } }
        Agent.onFinished = { runOnUiThread { refresh() } }
        logView.text = ""
        synchronized(Agent.log) { Agent.log.forEach { append(it) } }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        Agent.listener = null
    }

    private fun append(line: String) {
        logView.append(line + "\n\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun refresh() {
        status.text = if (AgentService.instance != null) "✅ خدمة الوصول شغالة" else "⚠️ خدمة الوصول مش مفعلة"
        stopBtn.isEnabled = Agent.running
    }
}

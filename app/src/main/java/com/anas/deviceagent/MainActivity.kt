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
import rikka.shizuku.Shizuku

class MainActivity : Activity() {
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var sendBtn: Button
    private lateinit var stopBtn: Button

    private val binderListener = Shizuku.OnBinderReceivedListener { runOnUiThread { refresh() } }
    private val deadListener = Shizuku.OnBinderDeadListener { runOnUiThread { refresh() } }
    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { refresh() } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun field(prefs: android.content.SharedPreferences, k: String, hint: String, secret: Boolean = false) =
        EditText(this).apply {
            this.hint = hint
            if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString(k, "")); setSingleLine()
        }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(32), dp(12), dp(12))
            isFocusableInTouchMode = true
        }
        status = TextView(this).apply { textSize = 13f }

        // Settings panel (collapsed by default once a provider is saved)
        val base2 = field(prefs, "base2", "المزود 1 Base URL (https://api.groq.com/openai/v1)")
        val model2 = field(prefs, "model2", "Model (openai/gpt-oss-120b)")
        val key2 = field(prefs, "key2", "API key", true)
        val base3 = field(prefs, "base3", "المزود 2 اختياري Base URL (https://openrouter.ai/api/v1)")
        val model3 = field(prefs, "model3", "Model")
        val key3 = field(prefs, "key3", "API key", true)
        val hidden = CheckBox(this).apply {
            text = "تشغيل واجهات التطبيقات على شاشة مخفية (تجريبي)"
            isChecked = prefs.getBoolean("hidden", true)
        }
        val saveBtn = Button(this).apply {
            text = "حفظ"
            setOnClickListener {
                prefs.edit()
                    .putString("base2", base2.text.toString().trim()).putString("model2", model2.text.toString().trim())
                    .putString("key2", key2.text.toString().trim())
                    .putString("base3", base3.text.toString().trim()).putString("model3", model3.text.toString().trim())
                    .putString("key3", key3.text.toString().trim())
                    .putBoolean("hidden", hidden.isChecked).apply()
                Toast.makeText(this@MainActivity, "تم الحفظ", Toast.LENGTH_SHORT).show()
            }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (prefs.getString("key2", "").isNullOrEmpty()) View.VISIBLE else View.GONE
            addView(base2); addView(model2); addView(key2); addView(base3); addView(model3); addView(key3)
            addView(hidden); addView(saveBtn)
        }
        val toggle = Button(this).apply {
            text = "⚙️ إعدادات"
            setOnClickListener { panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        val accBtn = Button(this).apply {
            text = "خدمة الوصول"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val shzBtn = Button(this).apply {
            text = "ربط Shizuku"
            setOnClickListener {
                try {
                    if (!Sh.binderReady()) {
                        Toast.makeText(this@MainActivity, "شغّل Shizuku الأول من تطبيقه", Toast.LENGTH_LONG).show()
                        packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { startActivity(it) }
                    } else Sh.request(7001)
                } catch (e: Throwable) {}
            }
        }
        val row1 = LinearLayout(this).apply {
            addView(toggle, LinearLayout.LayoutParams(0, -2, 1f))
            addView(shzBtn, LinearLayout.LayoutParams(0, -2, 1f))
            addView(accBtn, LinearLayout.LayoutParams(0, -2, 1f))
        }
        logView = TextView(this).apply { textSize = 15f; textDirection = View.TEXT_DIRECTION_FIRST_STRONG }
        scroll = ScrollView(this).apply { addView(logView) }
        val input = EditText(this).apply {
            hint = "اكتب الأمر"
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
        stopBtn = Button(this).apply { text = "إيقاف"; setOnClickListener { Agent.stop() } }
        val row2 = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, -2, 1f))
            addView(sendBtn); addView(stopBtn)
        }
        root.addView(status)
        root.addView(row1)
        root.addView(ScrollView(this).apply { addView(panel) }, LinearLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(row2)
        setContentView(root)
        try {
            Shizuku.addBinderReceivedListenerSticky(binderListener)
            Shizuku.addBinderDeadListener(deadListener)
            Shizuku.addRequestPermissionResultListener(permListener)
        } catch (e: Throwable) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            Shizuku.removeBinderReceivedListener(binderListener)
            Shizuku.removeBinderDeadListener(deadListener)
            Shizuku.removeRequestPermissionResultListener(permListener)
        } catch (e: Throwable) {}
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
        val acc = if (AgentService.instance != null) "✅ خدمة الوصول" else "⚠️ خدمة الوصول مش مفعلة"
        val shz = when {
            Sh.granted() -> "✅ Shizuku"
            Sh.binderReady() -> "⚠️ Shizuku شغال بس مش مصرّح (دوس ربط Shizuku)"
            else -> "⚠️ Shizuku مش شغال"
        }
        status.text = "$acc   |   $shz"
        stopBtn.isEnabled = Agent.running
    }
}

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
import android.app.AlertDialog
import android.app.WallpaperManager
import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

class MainActivity : Activity() {
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var sendBtn: Button
    private lateinit var stopBtn: Button
    private var pendingLocal: String? = null
    private var pendingShell: String? = null
    private lateinit var attachBtn: Button
    private lateinit var wpStatus: TextView

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
        val vmodel2 = field(prefs, "vmodel2", "موديل الصور للمزود 1 (meta-llama/llama-4-scout-17b-16e-instruct)")
        val base3 = field(prefs, "base3", "المزود 2 اختياري Base URL (https://openrouter.ai/api/v1)")
        val model3 = field(prefs, "model3", "Model")
        val key3 = field(prefs, "key3", "API key", true)
        val vmodel3 = field(prefs, "vmodel3", "موديل الصور للمزود 2 (meta-llama/llama-4-scout)")
        val saveBtn = Button(this).apply {
            text = "حفظ"
            setOnClickListener {
                prefs.edit()
                    .putString("base2", base2.text.toString().trim()).putString("model2", model2.text.toString().trim())
                    .putString("key2", key2.text.toString().trim())
                    .putString("base3", base3.text.toString().trim()).putString("model3", model3.text.toString().trim())
                    .putString("key3", key3.text.toString().trim())
                    .putString("vmodel2", vmodel2.text.toString().trim()).putString("vmodel3", vmodel3.text.toString().trim()).apply()
                Toast.makeText(this@MainActivity, "تم الحفظ", Toast.LENGTH_SHORT).show()
            }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (prefs.getString("key2", "").isNullOrEmpty()) View.VISIBLE else View.GONE
            addView(base2); addView(model2); addView(key2); addView(vmodel2); addView(base3); addView(model3); addView(key3); addView(vmodel3)
            addView(saveBtn)
        }
        val toggle = Button(this).apply {
            text = "⚙️ إعدادات"
            setOnClickListener { panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
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
                Agent.start(this@MainActivity, goal, pendingLocal, pendingShell)
                pendingLocal = null; pendingShell = null; attachBtn.text = "📎"
                refresh()
            }
        }
        stopBtn = Button(this).apply { text = "إيقاف"; setOnClickListener { Agent.stop() } }
        val row2 = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, -2, 1f))
            attachBtn = Button(this@MainActivity).apply {
                text = "📎"
                setOnClickListener {
                    startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), 21)
                }
            }
            addView(attachBtn); addView(sendBtn); addView(stopBtn)
        }
        // ---- agent panel
        val agentPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        agentPanel.addView(ScrollView(this).apply { addView(panel) }, LinearLayout.LayoutParams(-1, -2))
        agentPanel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        agentPanel.addView(row2)

        // ---- terminal panel
        val termOut = TextView(this).apply { textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true) }
        val termScroll = ScrollView(this).apply { addView(termOut) }
        val termIn = EditText(this).apply { hint = "ls /sdcard/Download"; setSingleLine(); setTextDirection(View.TEXT_DIRECTION_LTR) }
        fun runTerm(cmd: String) {
            if (cmd.isBlank()) return
            termOut.append("$ $cmd\n")
            Thread {
                val r = Agent.runShell(cmd, 6000)
                runOnUiThread { termOut.append(r + "\n\n"); termScroll.post { termScroll.fullScroll(View.FOCUS_DOWN) } }
            }.start()
        }
        val runBtn = Button(this).apply { text = "نفّذ"; setOnClickListener { val c = termIn.text.toString(); termIn.setText(""); runTerm(c) } }
        fun quick(label: String, cmd: String) = Button(this).apply { text = label; textSize = 11f; setOnClickListener { runTerm(cmd) } }
        val quickRow = LinearLayout(this).apply {
            addView(quick("بطارية", "dumpsys battery"), LinearLayout.LayoutParams(0, -2, 1f))
            addView(quick("ذاكرة", "cat /proc/meminfo"), LinearLayout.LayoutParams(0, -2, 1f))
            addView(quick("CPU", "top -b -n 1"), LinearLayout.LayoutParams(0, -2, 1f))
            addView(quick("تخزين", "df"), LinearLayout.LayoutParams(0, -2, 1f))
            addView(quick("تطبيقات", "pm list packages -3"), LinearLayout.LayoutParams(0, -2, 1f))
        }
        val termPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(quickRow)
            addView(termScroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(LinearLayout(this@MainActivity).apply {
                addView(termIn, LinearLayout.LayoutParams(0, -2, 1f)); addView(runBtn)
            })
        }

        // ---- wallpaper panel
        wpStatus = TextView(this).apply { textSize = 14f }
        val audioBtn = Button(this).apply {
            text = "1) إذن الصوت (للمؤثرات مع الإيقاع)"
            setOnClickListener { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 11) }
        }
        val chooseBtn = Button(this).apply {
            text = "2) فعّل خلفية الموسيقى"
            setOnClickListener {
                try {
                    startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this@MainActivity, MusicWallpaper::class.java)))
                } catch (e: Exception) {
                    try { startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) } catch (e2: Exception) {}
                }
            }
        }
        val listenBtn = Button(this).apply {
            text = "3) وضع الغلاف الاحتياطي (إذن قراءة الميديا)"
            setOnClickListener {
                Thread {
                    var done = false
                    if (Sh.granted()) {
                        val r = Sh.exec(listOf("cmd", "notification", "allow_listener", "$packageName/$packageName.MediaListener"))
                        done = r.startsWith("exit=0")
                    }
                    runOnUiThread {
                        if (!done) try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (e: Exception) {}
                        refresh()
                    }
                }.start()
            }
        }
        val baseBtn = Button(this).apply {
            text = "4) اجعل الصورة المرفقة (📎) أساس الخلفية"
            setOnClickListener {
                val p = pendingLocal
                if (p == null) { Toast.makeText(this@MainActivity, "ارفق صورة بزرار 📎 الأول", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                try { java.io.File(p).copyTo(java.io.File(filesDir, "wp_base.jpg"), true); Toast.makeText(this@MainActivity, "تم", Toast.LENGTH_SHORT).show() }
                catch (e: Exception) { Toast.makeText(this@MainActivity, "فشل", Toast.LENGTH_SHORT).show() }
            }
        }
        fun slider(label: String, init: Int, max: Int, apply: (WStyle, Float) -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply { text = label; textSize = 12f })
            addView(SeekBar(this@MainActivity).apply {
                this.max = max
                progress = init
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                        if (!u) return
                        val st = WStyle.load(prefs); apply(st, p / max.toFloat()); st.save(prefs)
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            })
        }
        val st0 = WStyle.load(prefs)
        val resetBtn = Button(this).apply {
            text = "رجّع الستايل الافتراضي (سينمائي)"
            setOnClickListener { WStyle.reset(prefs); Toast.makeText(this@MainActivity, "تم، اقفل الشاشة وافتحها", Toast.LENGTH_SHORT).show() }
        }
        fun sceneBtn(label: String, v: String) = Button(this).apply {
            text = label
            setOnClickListener {
                prefs.edit().putString("wp_scene", v).apply()
                val cur = try { WallpaperManager.getInstance(this@MainActivity).wallpaperInfo?.serviceInfo?.name } catch (e: Exception) { null }
                if (cur != null && (cur.endsWith("RingWallpaper") || cur.endsWith("LiquidWallpaper"))) pickLive(MusicWallpaper::class.java)
                else Toast.makeText(this@MainActivity, "تم، اقفل الشاشة وافتحها", Toast.LENGTH_SHORT).show()
            }
        }
        val ringBtn = Button(this).apply {
            text = "مشهد: الحلقة الزجاجية"
            setOnClickListener { pickLive(RingWallpaper::class.java) }
        }
        val wpPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
            addView(wpStatus); addView(audioBtn); addView(chooseBtn); addView(listenBtn); addView(baseBtn)
            addView(LinearLayout(this@MainActivity).apply {
                addView(sceneBtn("مشهد: صورتي", "image"), LinearLayout.LayoutParams(0, -2, 1f))
                addView(sceneBtn("مشهد: القمر 🌙", "moon"), LinearLayout.LayoutParams(0, -2, 1f))
                addView(sceneBtn("مشهد: الأكواد", "code"), LinearLayout.LayoutParams(0, -2, 1f))
            })
            addView(ringBtn)
            addView(Button(this@MainActivity).apply { text = "مشهد: الزجاج السائل الأسود 🖤"; setOnClickListener { pickLive(LiquidWallpaper::class.java) } })
            addView(Button(this@MainActivity).apply {
                text = "تفعيل الاستيريو الحقيقي للسائل (إذن تسجيل الصوت الداخلي)"
                setOnClickListener { startActivity(Intent(this@MainActivity, StereoActivity::class.java)) }
            })
            addView(Button(this@MainActivity).apply {
                text = "إيقاف الاستيريو الحقيقي"
                setOnClickListener { try { startService(Intent(this@MainActivity, StereoService::class.java).setAction("stop")) } catch (e: Throwable) {}; Toast.makeText(this@MainActivity, "اتوقف", Toast.LENGTH_SHORT).show() }
            })
            addView(android.widget.CheckBox(this@MainActivity).apply {
                text = "خلفية سودا لما مفيش حاجة شغالة"
                isChecked = prefs.getBoolean("wp_black_idle", true)
                setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("wp_black_idle", on).apply(); Toast.makeText(this@MainActivity, "تم، اقفل الشاشة وافتحها", Toast.LENGTH_SHORT).show() }
            })
            addView(android.widget.CheckBox(this@MainActivity).apply {
                text = "زرّين الأغنية اللي بعدها واللي قبلها (مشهد الأكواد)"
                isChecked = prefs.getBoolean("wp_skip", true)
                setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("wp_skip", on).apply(); Toast.makeText(this@MainActivity, "تم، اقفل الشاشة وافتحها", Toast.LENGTH_SHORT).show() }
            })
            addView(android.widget.CheckBox(this@MainActivity).apply {
                text = "إشعارات شيك على شاشة القفل (بدل إشعارات النظام، اقفل إشعارات القفل من الإعدادات)"
                isChecked = prefs.getBoolean("wp_lock_notifs", true)
                setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("wp_lock_notifs", on).apply(); Toast.makeText(this@MainActivity, "تم، اقفل الشاشة وافتحها", Toast.LENGTH_SHORT).show() }
            })
            addView(android.widget.CheckBox(this@MainActivity).apply {
                text = "تجريبي: إخفاء كارت الموسيقى بتاع النظام من شاشة القفل (يظهر كارت الخلفية بس)"
                isChecked = prefs.getBoolean("wp_hide_stock_media", true)
                setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("wp_hide_stock_media", on).apply(); Toast.makeText(this@MainActivity, "تم، اقفل الشاشة وشغل موسيقى", Toast.LENGTH_SHORT).show() }
            })
            addView(android.widget.CheckBox(this@MainActivity).apply {
                text = "وضع البطارية الذكي: يخفف الحركة تلقائيًا لما البطارية تقل عن 20% (كل الخلفيات)"
                isChecked = prefs.getBoolean("wp_batt_saver", true)
                setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("wp_batt_saver", on).apply(); Toast.makeText(this@MainActivity, "تم", Toast.LENGTH_SHORT).show() }
            })
            addView(android.widget.CheckBox(this@MainActivity).apply {
                text = "حافة بيضا منوّرة سايحة حوالين الكورة (الزجاج السائل)"
                isChecked = prefs.getBoolean("wp_liquid_rim", true)
                setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("wp_liquid_rim", on).apply(); Toast.makeText(this@MainActivity, "تم، اقفل الشاشة وافتحها", Toast.LENGTH_SHORT).show() }
            })
            addView(android.widget.CheckBox(this@MainActivity).apply {
                text = "حروف العنوان تطير وتتجمع (مشهد الأكواد)"
                isChecked = prefs.getBoolean("wp_fly", true)
                setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("wp_fly", on).apply(); Toast.makeText(this@MainActivity, "تم، اقفل الشاشة وافتحها", Toast.LENGTH_SHORT).show() }
            })
            addView(slider("مكان الموجات والأضواء: أفقي (الراديو)", (st0.ax * 100).toInt(), 100) { s, v -> s.ax = v })
            addView(slider("مكان الموجات والأضواء: رأسي (الراديو)", (st0.ay * 100).toInt(), 100) { s, v -> s.ay = v })
            addView(slider("شدة التأثير", ((st0.intensity - 0.3f) / 1.3f * 100).toInt(), 100) { s, v -> s.intensity = 0.3f + v * 1.3f })
            addView(resetBtn)
        }

        fun show(i: Int) {
            agentPanel.visibility = if (i == 0) View.VISIBLE else View.GONE
            termPanel.visibility = if (i == 1) View.VISIBLE else View.GONE
            wpPanel.visibility = if (i == 2) View.VISIBLE else View.GONE
            refresh()
        }
        fun tab(label: String, i: Int) = Button(this).apply { text = label; setOnClickListener { show(i) } }
        val tabs = LinearLayout(this).apply {
            addView(tab("الوكيل", 0), LinearLayout.LayoutParams(0, -2, 1f))
            addView(tab("Terminal", 1), LinearLayout.LayoutParams(0, -2, 1f))
            addView(tab("الخلفية", 2), LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(status)
        root.addView(row1)
        root.addView(tabs)
        root.addView(agentPanel, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(termPanel, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(wpPanel, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        try {
            Shizuku.addBinderReceivedListenerSticky(binderListener)
            Shizuku.addBinderDeadListener(deadListener)
            Shizuku.addRequestPermissionResultListener(permListener)
        } catch (e: Throwable) {}
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data
        if (req != 21 || res != RESULT_OK || uri == null) return
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val opt = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt)
                var ss = 1
                while (Math.max(opt.outWidth, opt.outHeight) / ss > 2400) ss *= 2
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = ss })!!
                val dir = getExternalFilesDir(null)!!
                val name = "img_" + System.currentTimeMillis() + ".jpg"
                val f = java.io.File(dir, name)
                f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                var shellPath: String? = null
                if (Sh.granted()) {
                    Sh.exec(listOf("mkdir", "-p", "/sdcard/Pictures/DeviceAgent"))
                    val r = Sh.exec(listOf("cp", f.absolutePath, "/sdcard/Pictures/DeviceAgent/$name"))
                    if (r.startsWith("exit=0")) shellPath = "/sdcard/Pictures/DeviceAgent/$name"
                }
                pendingLocal = f.absolutePath; pendingShell = shellPath
                runOnUiThread { attachBtn.text = "🖼✅" ; Toast.makeText(this, "الصورة اتضافت للأمر الجاي", Toast.LENGTH_SHORT).show() }
            } catch (e: Throwable) {
                runOnUiThread { Toast.makeText(this, "مقدرتش أقرا الصورة", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        refresh()
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
        Agent.confirmer = { msg ->
            val latch = CountDownLatch(1)
            val ok = BooleanArray(1)
            runOnUiThread {
                AlertDialog.Builder(this).setTitle("تأكيد مطلوب").setMessage(msg)
                    .setPositiveButton("وافق") { _, _ -> ok[0] = true; latch.countDown() }
                    .setNegativeButton("لا") { _, _ -> latch.countDown() }
                    .setOnCancelListener { latch.countDown() }.show()
            }
            latch.await(90, TimeUnit.SECONDS)
            ok[0]
        }
        logView.text = ""
        synchronized(Agent.log) { Agent.log.forEach { append(it) } }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        Agent.listener = null
        Agent.confirmer = null
    }

    private fun append(line: String) {
        logView.append(line + "\n\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun pickLive(c: Class<*>) {
        try {
            startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this, c)))
        } catch (e: Exception) {
            try { startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) } catch (e2: Exception) {}
        }
    }

    private fun refresh() {
        val shz = when {
            Sh.granted() -> "✅ Shizuku"
            Sh.binderReady() -> "⚠️ Shizuku شغال بس مش مصرّح (دوس ربط Shizuku)"
            else -> "⚠️ شغّل Shizuku-Next الأول (بعد إعادة تشغيل الموبايل بيقف)"
        }
        status.text = shz
        stopBtn.isEnabled = Agent.running
        val audio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val lis = (Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "").contains(packageName)
        wpStatus.text = (if (audio) "✅ إذن الصوت" else "⚠️ إذن الصوت ناقص") + "\n" +
            (if (lis) "✅ إذن الميديا (الغلاف)" else "ℹ️ إذن الميديا مش مفعل (احتياطي)") + "\n" +
            "الوضع الحالي: " + MusicState.mode + (if (MusicState.title.isNotBlank()) "\n🎵 " + MusicState.title else "")
    }
}

package uz.ustam.sms

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/** Ustam SMS — telefonni Ustam CRM'ga ulash va holatni ko'rsatish ekrani */
class MainActivity : Activity() {

    private val blue = Color.parseColor("#1D5FE0")
    private val navy = Color.parseColor("#0B1B3F")
    private val muted = Color.parseColor("#5A6785")
    private val light = Color.parseColor("#EAF1FF")
    private val green = Color.parseColor("#0F7A45")
    private val red = Color.parseColor("#C42B2B")

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private var statusBox: LinearLayout? = null

    private val tick = object : Runnable {
        override fun run() {
            if (Prefs.token(this@MainActivity) != null) fillStatus()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 21) window.statusBarColor = Color.WHITE
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.parseColor("#F5F8FD"))
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        render()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 3000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    // ---------- ekranlar ----------

    private fun render() {
        root.removeAllViews()
        statusBox = null
        header()
        if (Prefs.token(this) == null) pairScreen() else statusScreen()
    }

    private fun header() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        col.addView(text("Ustam SMS", 22f, true, navy))
        col.addView(text("SMS’lar shu telefon SIM kartasidan yuboriladi", 13f, false, muted))
        row.addView(icon)
        row.addView(col)
        root.addView(row)
        space(20)
    }

    private fun pairScreen() {
        val card = card()
        card.addView(text("Telefonni ulash", 18f, true, navy))
        space(card, 8)
        card.addView(
            text(
                "1. Ustam CRM ilovasini oching (Telegram bot).\n" +
                    "2. Sartaroshxona: Boshqaruv → SMS xizmati → «Telefon ulash».\n" +
                    "   Admin: Sozlama → SMS telefon → «Telefon ulash».\n" +
                    "3. Chiqqan 6 xonali kodni pastga yozing.",
                14f, false, muted
            )
        )
        space(card, 14)
        val code = EditText(this).apply {
            hint = "123456"
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            textSize = 28f
            gravity = Gravity.CENTER
            letterSpacing = 0.3f
            setTypeface(typeface, Typeface.BOLD)
            background = round(Color.WHITE, dp(14), Color.parseColor("#D5E2FB"))
            setPadding(dp(12), dp(14), dp(12), dp(14))
        }
        card.addView(code)
        space(card, 14)

        val api = EditText(this).apply {
            setText(Prefs.api(this@MainActivity))
            textSize = 11f
            visibility = View.GONE
            background = round(Color.WHITE, dp(10), Color.parseColor("#D5E2FB"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val pairBtn = button("Ulash", true) {}
        pairBtn.setOnClickListener {
            val c = code.text.toString().trim()
            if (c.length != 6) {
                toast("6 xonali kodni yozing")
                return@setOnClickListener
            }
            if (!hasSms()) {
                askPermissions()
                toast("Avval SMS yuborishga ruxsat bering, keyin «Ulash» ni qayta bosing")
                return@setOnClickListener
            }
            Prefs.setApi(this, api.text.toString())
            pairBtn.isEnabled = false
            pairBtn.text = "Ulanmoqda…"
            Thread {
                try {
                    val r = Api.post(
                        Prefs.api(this),
                        JSONObject().put("action", "d_pair").put("code", c)
                            .put("model", (Build.MANUFACTURER + " " + Build.MODEL).trim())
                    )
                    if (r.optBoolean("ok")) {
                        Prefs.savePair(this, r.getString("token"), r.optString("name"))
                        Prefs.addLog(this, "Ulandi: " + r.optString("name"))
                        runOnUiThread {
                            SmsService.start(this)
                            toast("Ulandi ✓")
                            render()
                            askBattery()
                        }
                    } else {
                        runOnUiThread {
                            pairBtn.isEnabled = true
                            pairBtn.text = "Ulash"
                            toast(r.optString("msg", "Ulanmadi"))
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        pairBtn.isEnabled = true
                        pairBtn.text = "Ulash"
                        toast("Internet yoki server xatosi: " + (e.message ?: ""))
                    }
                }
            }.start()
        }
        card.addView(pairBtn)
        space(card, 10)
        if (!hasSms()) {
            card.addView(button("SMS ruxsatini berish", false) { askPermissions() })
            space(card, 10)
        }
        val more = text("Qo‘shimcha: server manzili", 12f, false, blue)
        more.setOnClickListener { api.visibility = if (api.visibility == View.GONE) View.VISIBLE else View.GONE }
        card.addView(more)
        card.addView(api)
        root.addView(card)
        space(16)
        root.addView(
            text(
                "Muhim: telefon doim yoniq va internetga ulangan bo‘lsin. SIM kartada SMS paketi yoki balans bo‘lishi kerak.",
                12f, false, muted
            )
        )
    }

    private fun statusScreen() {
        val card = card()
        card.addView(text("Ulangan", 12f, true, muted))
        card.addView(text(Prefs.name(this).ifBlank { "Ustam CRM" }, 20f, true, navy))
        space(card, 12)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusBox = box
        card.addView(box)
        root.addView(card)
        fillStatus()
        space(14)

        val running = SmsService.running && Prefs.enabled(this)
        root.addView(button(if (running) "To‘xtatish" else "Ishga tushirish", !running) {
            if (SmsService.running && Prefs.enabled(this)) {
                Prefs.setEnabled(this, false)
                SmsService.stop(this)
                Prefs.setStatus(this, "To‘xtatilgan")
            } else {
                if (!hasSms()) {
                    askPermissions()
                    return@button
                }
                Prefs.setEnabled(this, true)
                SmsService.start(this)
            }
            handler.postDelayed({ render() }, 400)
        })
        space(10)
        if (!hasSms()) {
            root.addView(button("SMS ruxsatini berish", false) { askPermissions() })
            space(10)
        }
        if (!ignoringBattery()) {
            root.addView(button("Batareya cheklovini o‘chirish (tavsiya)", false) { askBattery() })
            space(10)
        }
        root.addView(button("Telefonni uzish", false) {
            AlertDialog.Builder(this)
                .setTitle("Telefonni uzasizmi?")
                .setMessage("Bu telefon endi SMS yubormaydi. Qayta ulash uchun yangi kod kerak bo‘ladi.")
                .setPositiveButton("Uzish") { _, _ -> unpair() }
                .setNegativeButton("Bekor", null)
                .show()
        })
        space(18)
        root.addView(text("Oxirgi amallar", 15f, true, navy))
        space(6)
        val log = Prefs.log(this)
        root.addView(
            text(if (log.isEmpty()) "Hali SMS yuborilmagan" else log.take(20).joinToString("\n"), 12f, false, muted)
        )
    }

    private fun fillStatus() {
        val box = statusBox ?: return
        box.removeAllViews()
        val on = SmsService.running && Prefs.enabled(this)
        val st = Prefs.status(this)
        val color = if (!on) red else if (st.startsWith("Xato")) red else green
        box.addView(text((if (on) "● " else "○ ") + (if (on) st.ifBlank { "Ishlayapti" } else "To‘xtatilgan"), 15f, true, color))
        if (Prefs.statusAt(this).isNotBlank()) box.addView(text("Oxirgi tekshiruv: " + Prefs.statusAt(this), 12f, false, muted))
        space(box, 10)
        box.addView(
            text(
                "Bugun yuborildi: ${Prefs.sentToday(this)}   ·   Xato: ${Prefs.failedToday(this)}\nJami: ${Prefs.sentTotal(this)}",
                14f, false, navy
            )
        )
    }

    private fun unpair() {
        val token = Prefs.token(this) ?: return
        Thread {
            try {
                Api.post(Prefs.api(this), JSONObject().put("action", "d_unpair").put("token", token))
            } catch (_: Exception) {
            }
            Prefs.clearPair(this)
            Prefs.addLog(this, "Telefon uzildi")
            runOnUiThread {
                SmsService.stop(this)
                render()
            }
        }.start()
    }

    // ---------- ruxsatlar ----------

    private fun hasSms(): Boolean =
        Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        if (Build.VERSION.SDK_INT < 23) return
        val list = mutableListOf(Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(list.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun ignoringBattery(): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun askBattery() {
        if (ignoringBattery()) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    // ---------- kichik UI yordamchilar ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float, bold: Boolean, color: Int): TextView = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(0f, 1.15f)
    }

    private fun round(fill: Int, radius: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius.toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(Color.WHITE, dp(18), Color.parseColor("#E3E9F5"))
        setPadding(dp(18), dp(18), dp(18), dp(18))
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (primary) Color.WHITE else navy)
        background = round(if (primary) blue else Color.WHITE, dp(14), if (primary) null else Color.parseColor("#D5E2FB"))
        minHeight = dp(52)
        stateListAnimator = null
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun space(h: Int) = space(root, h)

    private fun space(parent: LinearLayout, h: Int) {
        parent.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) })
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

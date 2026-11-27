package uz.ustam.sms

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ilova sozlamalari va hisoblagichlar */
object Prefs {
    /** Ustam CRM serveri (Apps Script Web App). Ilovada o'zgartirsa bo'ladi. */
    const val DEFAULT_API =
        "https://script.google.com/macros/s/AKfycby2c_cJHRXyU9NghPrphMZ5D6Yjb6TKERQS62WireAhip8x7JI0cVIzZYn-bAHAYAEs3g/exec"

    private fun sp(c: Context): SharedPreferences = c.getSharedPreferences("ustam_sms", Context.MODE_PRIVATE)

    fun api(c: Context): String = sp(c).getString("api", DEFAULT_API) ?: DEFAULT_API
    fun setApi(c: Context, v: String) = sp(c).edit().putString("api", v.trim()).apply()

    fun token(c: Context): String? = sp(c).getString("token", null)
    fun name(c: Context): String = sp(c).getString("name", "") ?: ""
    fun savePair(c: Context, token: String, name: String) =
        sp(c).edit().putString("token", token).putString("name", name).putBoolean("enabled", true).apply()

    fun clearPair(c: Context) =
        sp(c).edit().remove("token").remove("name").putBoolean("enabled", false).apply()

    fun setName(c: Context, name: String) = sp(c).edit().putString("name", name).apply()

    fun enabled(c: Context): Boolean = sp(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    fun now(): String = SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date())

    fun sentToday(c: Context): Int =
        if (sp(c).getString("day", "") == today()) sp(c).getInt("sentToday", 0) else 0

    fun failedToday(c: Context): Int =
        if (sp(c).getString("day", "") == today()) sp(c).getInt("failToday", 0) else 0

    fun addCounts(c: Context, ok: Int, fail: Int) {
        val p = sp(c)
        val same = p.getString("day", "") == today()
        p.edit()
            .putString("day", today())
            .putInt("sentToday", (if (same) p.getInt("sentToday", 0) else 0) + ok)
            .putInt("failToday", (if (same) p.getInt("failToday", 0) else 0) + fail)
            .putInt("sentTotal", p.getInt("sentTotal", 0) + ok)
            .apply()
    }

    fun sentTotal(c: Context): Int = sp(c).getInt("sentTotal", 0)

    fun status(c: Context): String = sp(c).getString("status", "") ?: ""
    fun setStatus(c: Context, v: String) = sp(c).edit().putString("status", v).putString("statusAt", now()).apply()
    fun statusAt(c: Context): String = sp(c).getString("statusAt", "") ?: ""

    fun log(c: Context): List<String> =
        (sp(c).getString("log", "") ?: "").split("\n").filter { it.isNotBlank() }

    fun addLog(c: Context, line: String) {
        val list = (listOf(now() + "  " + line) + log(c)).take(40)
        sp(c).edit().putString("log", list.joinToString("\n")).apply()
    }
}

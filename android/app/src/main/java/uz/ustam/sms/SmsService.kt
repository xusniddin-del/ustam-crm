package uz.ustam.sms

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fon xizmati: har daqiqada Ustam CRM serveridan navbatdagi SMS'larni oladi,
 * telefon SIM kartasidan yuboradi va natijani serverga qaytaradi.
 */
class SmsService : Service() {

    companion object {
        const val CHANNEL = "ustam_sms"
        const val NOTIF_ID = 7
        @Volatile
        var running = false

        fun start(c: Context) {
            val i = Intent(c, SmsService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, SmsService::class.java))
        }
    }

    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val n = buildNotification("Ishga tushmoqda…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
        if (Prefs.token(this) == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (wake == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UstamSMS:worker").also { it.acquire() }
        }
        if (worker?.isAlive != true) {
            running = true
            worker = Thread { loop() }.also {
                it.isDaemon = true
                it.start()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        try {
            wake?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wake = null
        super.onDestroy()
    }

    private fun loop() {
        var n = 0
        while (running) {
            var gotAny = false
            try {
                val token = Prefs.token(this) ?: break
                val r = Api.post(
                    Prefs.api(this),
                    JSONObject().put("action", "d_pull").put("token", token).put("full", n % 10 == 0).put("max", 10)
                )
                if (r.optString("error") == "unpaired") {
                    Prefs.clearPair(this)
                    Prefs.setStatus(this, "Uzilgan")
                    Prefs.addLog(this, "Telefon serverdan uzildi")
                    break
                }
                if (r.has("error")) throw Exception(r.optString("msg", r.optString("error")))
                val name = r.optString("name")
                if (name.isNotBlank()) Prefs.setName(this, name)
                Prefs.setStatus(this, if (r.optBoolean("paused")) "To‘xtatilgan: obuna yoki tarif" else "Ishlayapti")

                val list = r.optJSONArray("sms") ?: JSONArray()
                if (list.length() > 0) {
                    gotAny = true
                    val results = JSONArray()
                    var ok = 0
                    var fail = 0
                    for (i in 0 until list.length()) {
                        if (!running) break
                        val m = list.getJSONObject(i)
                        val id = m.getString("id")
                        val phone = m.getString("phone")
                        val err = sendOne(id, phone, m.getString("text"))
                        results.put(JSONObject().put("id", id).put("ok", err == null).put("err", err ?: ""))
                        if (err == null) {
                            ok++
                            Prefs.addLog(this, "✓ $phone")
                        } else {
                            fail++
                            Prefs.addLog(this, "✗ $phone — $err")
                        }
                        Prefs.addCounts(this, if (err == null) 1 else 0, if (err == null) 0 else 1)
                        updateNotification()
                        Thread.sleep(4000)
                    }
                    if (results.length() > 0) {
                        Api.post(
                            Prefs.api(this),
                            JSONObject().put("action", "d_report").put("token", token).put("results", results)
                        )
                    }
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Prefs.setStatus(this, "Xato: " + (e.message ?: "internet yo‘q"))
            }
            updateNotification()
            n++
            try {
                Thread.sleep(if (gotAny) 3000L else 60000L)
            } catch (e: InterruptedException) {
                break
            }
        }
        running = false
        stopSelf()
    }

    private fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else legacySmsManager()

    @Suppress("DEPRECATION")
    private fun legacySmsManager(): SmsManager = SmsManager.getDefault()

    /** Bitta SMS yuboradi. Muvaffaqiyatli bo'lsa null, aks holda xato matnini qaytaradi. */
    private fun sendOne(id: String, phone: String, text: String): String? {
        val action = "uz.ustam.sms.SENT_$id"
        val errors = AtomicInteger(0)
        val lastCode = AtomicInteger(Activity.RESULT_OK)
        val sm: SmsManager
        val parts: ArrayList<String>
        try {
            sm = smsManager()
            parts = sm.divideMessage(text)
        } catch (e: Exception) {
            return "SMS moduli topilmadi"
        }
        val latch = CountDownLatch(parts.size)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (resultCode != Activity.RESULT_OK) {
                    errors.incrementAndGet()
                    lastCode.set(resultCode)
                }
                latch.countDown()
            }
        }
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter)
        try {
            val intents = ArrayList<PendingIntent>()
            for (k in parts.indices) {
                val it = Intent(action).setPackage(packageName)
                intents.add(PendingIntent.getBroadcast(this, (id.hashCode() * 31) + k, it, PendingIntent.FLAG_IMMUTABLE))
            }
            sm.sendMultipartTextMessage(phone, null, parts, intents, null)
            if (!latch.await(90, TimeUnit.SECONDS)) return "Javob kelmadi (SIM yoki tarmoq)"
            return if (errors.get() == 0) null else errorText(lastCode.get())
        } catch (e: SecurityException) {
            return "SMS yuborishga ruxsat berilmagan"
        } catch (e: Exception) {
            return e.message ?: "Yuborilmadi"
        } finally {
            try {
                unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
        }
    }

    private fun errorText(code: Int): String = when (code) {
        1 -> "Yuborilmadi (balans, SMS paketi yoki SIM)"
        2 -> "Telefon tarmog‘i o‘chiq (parvoz rejimi?)"
        3 -> "SMS matni xato"
        4 -> "Tarmoq yo‘q"
        5 -> "SMS limiti tugagan"
        else -> "Xato kodi $code"
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Ustam SMS", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun builder(): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else legacyBuilder()

    @Suppress("DEPRECATION")
    private fun legacyBuilder(): Notification.Builder = Notification.Builder(this)

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return builder()
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Ustam SMS — " + Prefs.name(this))
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification("Bugun ${Prefs.sentToday(this)} ta yuborildi · ${Prefs.status(this)}"))
    }
}

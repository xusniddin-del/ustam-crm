package uz.ustam.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Telefon qayta yonganda yoki ilova yangilanganda xizmatni o'zi ishga tushiradi */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs.token(context) != null && Prefs.enabled(context)) {
            SmsService.start(context)
        }
    }
}

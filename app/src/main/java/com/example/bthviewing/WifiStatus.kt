package com.example.bthviewing

import android.content.Context
import android.net.wifi.WifiManager

/**
 * Возвращает текст статуса Wi-Fi для отображения в UI.
 * Использует только isWifiEnabled (ACCESS_WIFI_STATE, normal).
 * Не запрашивает SSID/частоту — не требует NEARBY_WIFI_DEVICES или ACCESS_FINE_LOCATION.
 */
object WifiStatus {

    fun text(context: Context): String {
        val wm = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return "Wi-Fi: неизвестно"
        return try {
            if (wm.isWifiEnabled) "Wi-Fi: включён" else "Wi-Fi: выключен"
        } catch (_: Exception) {
            "Wi-Fi: неизвестно"
        }
    }
}
package com.example.daybook

import android.content.Context

object DaybookApiConfig {
    /**
     * Default base URL.
     * http://10.0.2.2:8080 is localhost for the Android Emulator.
     * For a physical device on Wi-Fi, change this to your PC's IP, e.g. "http://192.168.1.100:8080".
     */
    const val DEFAULT_BASE_URL = "http://10.0.2.2:8080"
    private const val PREF_KEY_BASE_URL = "api_base_url"

    fun getBaseUrl(context: Context): String {
        val prefs = context.getSharedPreferences("daybook_prefs", Context.MODE_PRIVATE)
        return prefs.getString(PREF_KEY_BASE_URL, DEFAULT_BASE_URL)?.trimEnd('/') ?: DEFAULT_BASE_URL
    }

    fun setBaseUrl(context: Context, url: String) {
        val clean = url.trim().trimEnd('/')
        context.getSharedPreferences("daybook_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_KEY_BASE_URL, clean)
            .apply()
    }
}

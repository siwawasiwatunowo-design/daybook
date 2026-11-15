package com.example.daybook

import android.content.Context

object DaybookApiConfig {
    /**
     * Default base URL.
     * Uses the public ngrok endpoint by default, accessible from both emulator and physical device anywhere.
     */
    const val DEFAULT_BASE_URL = "https://tubeless-gutless-unlined.ngrok-free.dev"
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

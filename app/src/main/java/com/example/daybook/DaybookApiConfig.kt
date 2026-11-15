package com.example.daybook

import android.content.Context

object DaybookApiConfig {
    /**
     * Default base URL, injected from GitHub Actions repository variable DAYBOOK_API_BASE_URL via BuildConfig.
     */
    val DEFAULT_BASE_URL: String = try {
        BuildConfig.DAYBOOK_API_BASE_URL.ifBlank { "https://tubeless-gutless-unlined.ngrok-free.dev" }
    } catch (_: Throwable) {
        "https://tubeless-gutless-unlined.ngrok-free.dev"
    }
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

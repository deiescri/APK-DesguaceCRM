package com.desguace.crm

import android.content.Context

object CrmDeviceToken {
    private const val PREFS = "desguace_crm_secure"
    private const val KEY = "android_device_token"

    fun get(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "")?.trim().orEmpty()
    }

    fun set(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, token.trim()).apply()
    }
}
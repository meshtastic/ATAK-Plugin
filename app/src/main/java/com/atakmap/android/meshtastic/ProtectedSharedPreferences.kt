package com.atakmap.android.meshtastic

import android.content.SharedPreferences

/**
 *
 */
class ProtectedSharedPreferences(
    private val preferences: SharedPreferences
) : SharedPreferences by preferences {
    override fun getInt(key: String?, defValue: Int): Int {
        return try {
            preferences.getInt(key, defValue);
        } catch (exception: ClassCastException) {
            defValue
        }
    }
}

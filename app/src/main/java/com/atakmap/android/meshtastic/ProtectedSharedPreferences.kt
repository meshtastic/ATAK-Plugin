package com.atakmap.android.meshtastic

import android.content.SharedPreferences

/**
 *
 */
class ProtectedSharedPreferences(
    private val preferences: SharedPreferences
) : SharedPreferences by preferences {
    override fun getInt(key: String?, defValue: Int) = try {
        preferences.getInt(key, defValue)
    } catch (exception: ClassCastException) {
        val strValue = preferences.getString(key, defValue.toString())
        try {
            strValue?.toInt() ?: defValue
        } catch (exception: NumberFormatException) {
            defValue
        }
    }

}

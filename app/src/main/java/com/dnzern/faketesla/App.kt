package com.dnzern.faketesla

import android.app.Application
import android.content.Context
import android.content.SharedPreferences

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        ObdManager.init(this)
    }
}

/** User settings, stored on the phone. */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(c: Context) {
        sp = c.getSharedPreferences("fake_tesla", Context.MODE_PRIVATE)
    }

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v.trim()).apply()

    /** Minimum battery % when arriving at the destination. */
    var reservePct: Int
        get() = sp.getInt("reserve_pct", 10)
        set(v) = sp.edit().putInt("reserve_pct", v).apply()

    /** Minimum battery % when arriving at a charger. */
    var chargerArrivePct: Int
        get() = sp.getInt("charger_arrive_pct", 8)
        set(v) = sp.edit().putInt("charger_arrive_pct", v).apply()

    /** Highest % to charge to on the road. */
    var maxChargePct: Int
        get() = sp.getInt("max_charge_pct", 80)
        set(v) = sp.edit().putInt("max_charge_pct", v).apply()

    /** Ignore chargers slower than this (kW). */
    var minChargerKw: Int
        get() = sp.getInt("min_charger_kw", 50)
        set(v) = sp.edit().putInt("min_charger_kw", v).apply()

    /** Multiplies the modelled consumption (1.0 = model as is). */
    var consumptionFactor: Float
        get() = sp.getFloat("consumption_factor", 1.0f)
        set(v) = sp.edit().putFloat("consumption_factor", v).apply()

    /** Battery % used for planning when the car is not connected. */
    var manualSoc: Int
        get() = sp.getInt("manual_soc", 80)
        set(v) = sp.edit().putInt("manual_soc", v).apply()

    var invertCurrent: Boolean
        get() = sp.getBoolean("invert_current", false)
        set(v) = sp.edit().putBoolean("invert_current", v).apply()
}

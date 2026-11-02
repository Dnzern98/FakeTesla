package com.dnzern.faketesla

import android.app.Application
import android.content.Context
import android.content.SharedPreferences

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        org.maplibre.android.MapLibre.getInstance(this)
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

    /** Optional override typed in the app; empty = the key built into the app. */
    var customApiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v.trim()).apply()

    /** The key used for Google web services. */
    val apiKey: String
        get() = customApiKey.ifBlank { BuildConfig.MAPS_API_KEY }

    /** Use Google (traffic, better search) instead of the free OpenStreetMap services. */
    var useGoogle: Boolean
        get() = sp.getBoolean("use_google", false)
        set(v) = sp.edit().putBoolean("use_google", v).apply()

    /** Free TomTom key for live traffic (pasted in the menu). */
    var tomtomKey: String
        get() = sp.getString("tomtom_key", "") ?: ""
        set(v) = sp.edit().putString("tomtom_key", v.trim()).apply()

    var home: PlaceRef?
        get() = readPlace("home")
        set(v) = writePlace("home", v)

    var work: PlaceRef?
        get() = readPlace("work")
        set(v) = writePlace("work", v)

    private fun placeToJson(p: PlaceRef): org.json.JSONObject {
        val o = org.json.JSONObject().put("name", p.name).put("placeId", p.placeId ?: "")
        val ll = p.latLng
        if (ll != null) o.put("lat", ll.lat).put("lng", ll.lng)
        return o
    }

    private fun placeFromJson(o: org.json.JSONObject): PlaceRef {
        val ll = if (o.has("lat") && o.has("lng")) LatLng(o.getDouble("lat"), o.getDouble("lng")) else null
        return PlaceRef(o.getString("name"), o.optString("placeId").ifBlank { null }, ll)
    }

    private fun readPlace(key: String): PlaceRef? {
        val s = sp.getString(key, null) ?: return null
        return try {
            placeFromJson(org.json.JSONObject(s))
        } catch (_: Exception) {
            null
        }
    }

    private fun writePlace(key: String, p: PlaceRef?) {
        if (p == null) {
            sp.edit().remove(key).apply()
            return
        }
        sp.edit().putString(key, placeToJson(p).toString()).apply()
    }

    /** Last few destinations, newest first. */
    fun recents(): List<PlaceRef> {
        val s = sp.getString("recents", null) ?: return emptyList()
        return try {
            val a = org.json.JSONArray(s)
            List(a.length()) { placeFromJson(a.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addRecent(p: PlaceRef) {
        if (p.placeId == null && p.latLng == null) return
        val list = (listOf(p) + recents().filter { it.name != p.name }).take(5)
        val a = org.json.JSONArray()
        for (r in list) a.put(placeToJson(r))
        sp.edit().putString("recents", a.toString()).apply()
    }

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

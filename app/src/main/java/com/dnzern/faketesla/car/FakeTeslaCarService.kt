package com.dnzern.faketesla.car

import android.content.Intent
import android.net.Uri
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.lifecycleScope
import com.dnzern.faketesla.LatLng
import com.dnzern.faketesla.ObdManager
import com.dnzern.faketesla.TripRepo
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Android Auto entry point. Shows battery, range and the next charging stop on the car screen. */
class FakeTeslaCarService : CarAppService() {

    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = MainCarScreen(carContext)
    }
}

class MainCarScreen(carContext: CarContext) : Screen(carContext) {

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    init {
        lifecycleScope.launch {
            while (isActive) {
                delay(5_000L)
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val pane = Pane.Builder()

        val soc = ObdManager.socNow()
        val range = ObdManager.estimatedRangeKm()
        val batteryRow = Row.Builder()
        if (soc != null) {
            batteryRow.setTitle("Batarya %${soc.roundToInt()}")
            batteryRow.addText("Tahmini menzil ${range?.roundToInt() ?: "-"} km")
        } else {
            batteryRow.setTitle(if (ObdManager.connected.value) "Araç verisi bekleniyor" else "Araç bağlı değil")
            batteryRow.addText("Telefondaki uygulamadan OBD adaptörüne bağlan")
        }
        pane.addRow(batteryRow.build())

        val plan = TripRepo.plan.value
        if (plan == null) {
            pane.addRow(
                Row.Builder()
                    .setTitle("Rota planı yok")
                    .addText("Telefondan bir hedef seçip planla")
                    .build()
            )
        } else {
            pane.addRow(
                Row.Builder()
                    .setTitle("Varış ${timeFmt.format(Date(plan.arrivalAtMs))} · %${plan.arrivalSoc}")
                    .addText(plan.destName)
                    .build()
            )
            val next = plan.stops.firstOrNull()
            if (next != null) {
                pane.addRow(
                    Row.Builder()
                        .setTitle("Şarj: ${next.charger.name}")
                        .addText("Varışta %${next.arriveSoc} → %${next.departSoc} · ${next.chargeMin.roundToInt()} dk")
                        .build()
                )
                pane.addAction(
                    Action.Builder()
                        .setTitle("Şarja git")
                        .setOnClickListener { navigateTo(next.charger.loc) }
                        .build()
                )
            } else {
                pane.addRow(Row.Builder().setTitle("Şarj durağı gerekmiyor").build())
            }
            val dest = plan.routePts.lastOrNull()
            if (dest != null) {
                pane.addAction(
                    Action.Builder()
                        .setTitle("Hedefe git")
                        .setOnClickListener { navigateTo(dest) }
                        .build()
                )
            }
        }

        return PaneTemplate.Builder(pane.build())
            .setTitle("FakeTesla")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    private fun navigateTo(p: LatLng) {
        try {
            carContext.startCarApp(Intent(CarContext.ACTION_NAVIGATE, Uri.parse("geo:${p.str()}")))
        } catch (e: Exception) {
            ObdManager.log("Navigasyon açılamadı: ${e.message}")
        }
    }
}

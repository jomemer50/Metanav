package com.metanav.app.engine

import android.content.Context
import com.metanav.app.frames.SourceKind
import com.metanav.core.CameraGeometry
import com.metanav.core.ReasonerConfig
import com.metanav.core.Units

/** User-tunable preferences, persisted in SharedPreferences. */
data class Settings(
    val source: SourceKind = SourceKind.GLASSES,
    val units: Units = Units.METERS,
    val voice: Boolean = true,
    val haptics: Boolean = true,
    val announceClear: Boolean = true,
    /** Camera height above the ground in meters (glasses ~1.55, phone at chest ~1.3). */
    val cameraHeightMeters: Float = 1.55f,
    /** How far ahead to warn, in meters. */
    val alertDistanceMeters: Float = 3.0f,
    val showPreview: Boolean = true,
) {
    fun toReasonerConfig(): ReasonerConfig = ReasonerConfig(
        geometry = CameraGeometry(heightMeters = cameraHeightMeters),
        alertDistanceMeters = alertDistanceMeters,
        announceClear = announceClear,
        units = units,
    )

    companion object {
        private const val PREFS = "metanav"

        fun load(context: Context): Settings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = Settings()
            return Settings(
                source = runCatching { SourceKind.valueOf(p.getString("source", d.source.name)!!) }.getOrDefault(d.source),
                units = runCatching { Units.valueOf(p.getString("units", d.units.name)!!) }.getOrDefault(d.units),
                voice = p.getBoolean("voice", d.voice),
                haptics = p.getBoolean("haptics", d.haptics),
                announceClear = p.getBoolean("announceClear", d.announceClear),
                cameraHeightMeters = p.getFloat("cameraHeight", d.cameraHeightMeters),
                alertDistanceMeters = p.getFloat("alertDistance", d.alertDistanceMeters),
                showPreview = p.getBoolean("showPreview", d.showPreview),
            )
        }

        fun save(context: Context, s: Settings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("source", s.source.name)
                .putString("units", s.units.name)
                .putBoolean("voice", s.voice)
                .putBoolean("haptics", s.haptics)
                .putBoolean("announceClear", s.announceClear)
                .putFloat("cameraHeight", s.cameraHeightMeters)
                .putFloat("alertDistance", s.alertDistanceMeters)
                .putBoolean("showPreview", s.showPreview)
                .apply()
        }
    }
}

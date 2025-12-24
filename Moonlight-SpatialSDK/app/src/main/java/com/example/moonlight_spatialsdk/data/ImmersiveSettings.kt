package com.example.moonlight_spatialsdk.data

import android.content.Context

/**
 * Data class representing user-configurable immersive mode settings.
 * 
 * These settings control which immersive features are activated when the user
 * toggles "Immersive Mode" on the ButtonShelf. Each feature can be independently
 * enabled or disabled through the Immersive Options dialog.
 * 
 * Settings are persisted to SharedPreferences and survive app restarts.
 * All features default to disabled (user opts-in).
 */
data class ImmersiveSettings(
    /** Enable spatialized audio from the video panel position */
    val spatialAudioEnabled: Boolean = false,
    /** Dim the passthrough view when streaming to reduce visual distraction */
    val roomDimmingEnabled: Boolean = false,
    /** Enable emissive lighting effect from the video panel */
    val lightingEmissionEnabled: Boolean = false,
    /** Enable video reflections on MRUK room surfaces (walls, floor, ceiling) */
    val reflectionsEnabled: Boolean = false
) {
    companion object {
        private const val PREFS_NAME = "immersive_settings"
        private const val KEY_SPATIAL_AUDIO = "spatial_audio_enabled"
        private const val KEY_ROOM_DIMMING = "room_dimming_enabled"
        private const val KEY_LIGHTING_EMISSION = "lighting_emission_enabled"
        private const val KEY_REFLECTIONS = "reflections_enabled"

        /**
         * Load immersive settings from SharedPreferences.
         * Returns default settings (all disabled) if no saved preferences exist.
         * 
         * @param context Android context for accessing SharedPreferences
         * @return ImmersiveSettings with loaded or default values
         */
        fun load(context: Context): ImmersiveSettings {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return ImmersiveSettings(
                spatialAudioEnabled = prefs.getBoolean(KEY_SPATIAL_AUDIO, false),
                roomDimmingEnabled = prefs.getBoolean(KEY_ROOM_DIMMING, false),
                lightingEmissionEnabled = prefs.getBoolean(KEY_LIGHTING_EMISSION, false),
                reflectionsEnabled = prefs.getBoolean(KEY_REFLECTIONS, false)
            )
        }

        /**
         * Save immersive settings to SharedPreferences.
         * Changes are applied asynchronously.
         * 
         * @param context Android context for accessing SharedPreferences
         * @param settings ImmersiveSettings to persist
         */
        fun save(context: Context, settings: ImmersiveSettings) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SPATIAL_AUDIO, settings.spatialAudioEnabled)
                .putBoolean(KEY_ROOM_DIMMING, settings.roomDimmingEnabled)
                .putBoolean(KEY_LIGHTING_EMISSION, settings.lightingEmissionEnabled)
                .putBoolean(KEY_REFLECTIONS, settings.reflectionsEnabled)
                .apply()
        }
    }
}


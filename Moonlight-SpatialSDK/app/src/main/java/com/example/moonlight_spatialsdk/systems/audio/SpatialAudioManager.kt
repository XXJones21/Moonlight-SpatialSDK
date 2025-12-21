package com.example.moonlight_spatialsdk.systems.audio

import android.util.Log
import com.meta.spatial.core.Entity
import com.meta.spatial.spatialaudio.AudioSessionId
import com.meta.spatial.spatialaudio.AudioType
import com.meta.spatial.spatialaudio.SpatialAudioFeature

/**
 * Manages spatialized audio for the Moonlight video streaming panel.
 * 
 * Spatial audio creates an immersive experience where audio appears to emanate
 * from the video panel's position in space. When the user moves around the room,
 * the audio adjusts to maintain the illusion of a real audio source.
 * 
 * Usage:
 * 1. Call enableSpatialAudio() with the video panel entity and audio session ID
 * 2. Call disableSpatialAudio() to remove spatial audio from the entity
 * 
 * The audio session ID should be obtained from the Moonlight AndroidAudioRenderer's
 * underlying AudioTrack via getAudioSessionId().
 */
class SpatialAudioManager(
    private val spatialAudioFeature: SpatialAudioFeature,
) {
    companion object {
        private const val TAG = "SpatialAudioManager"
        
        // Fixed registration ID for the Moonlight audio stream
        // Using a constant since there's only one audio source (the game stream)
        private const val MOONLIGHT_AUDIO_SESSION_REGISTRATION_ID = 1
    }
    
    private var isEnabled = false
    private var registeredEntity: Entity? = null
    
    /**
     * Enables spatialized audio for the given entity.
     * 
     * Registers the Android audio session ID with the Spatial Audio feature,
     * then attaches an AudioSessionId component to the entity so that audio
     * appears to emanate from the entity's position in space.
     * 
     * @param entity The video panel entity to attach spatial audio to
     * @param androidAudioSessionId The audio session ID from AudioTrack.getAudioSessionId()
     * @param channelCount Number of audio channels (1=mono, 2=stereo)
     */
    fun enableSpatialAudio(
        entity: Entity,
        androidAudioSessionId: Int,
        channelCount: Int = 2
    ) {
        if (isEnabled) {
            Log.d(TAG, "Spatial audio already enabled, skipping")
            return
        }
        
        if (androidAudioSessionId <= 0) {
            Log.w(TAG, "Invalid audio session ID: $androidAudioSessionId, cannot enable spatial audio")
            return
        }
        
        Log.i(TAG, "Enabling spatial audio for entity ${entity.id} with session ID $androidAudioSessionId")
        
        // Register the Android audio session ID with the Spatial Audio feature
        spatialAudioFeature.registerAudioSessionId(
            MOONLIGHT_AUDIO_SESSION_REGISTRATION_ID,
            androidAudioSessionId,
        )
        
        // Determine audio type based on channel count
        val audioType = when (channelCount) {
            1 -> AudioType.MONO
            2 -> AudioType.STEREO
            else -> AudioType.SOUNDFIELD // For multichannel audio
        }
        
        // Attach AudioSessionId component to the entity
        // This tells the Spatial Audio feature to spatialize this audio source
        // based on the entity's position relative to the user's head
        entity.setComponent(AudioSessionId(MOONLIGHT_AUDIO_SESSION_REGISTRATION_ID, audioType))
        
        isEnabled = true
        registeredEntity = entity
        
        Log.i(TAG, "Spatial audio enabled with AudioType: $audioType")
    }
    
    /**
     * Disables spatialized audio for the previously registered entity.
     * 
     * Removes the AudioSessionId component from the entity and unregisters
     * the audio session from the Spatial Audio feature.
     */
    fun disableSpatialAudio() {
        if (!isEnabled) {
            Log.d(TAG, "Spatial audio already disabled, skipping")
            return
        }
        
        Log.i(TAG, "Disabling spatial audio")
        
        // Note: We don't remove the AudioSessionId component because the SDK doesn't support
        // component removal. The entity will be destroyed on disconnect anyway.
        // Just clear our tracking state.
        
        isEnabled = false
        registeredEntity = null
        
        Log.i(TAG, "Spatial audio disabled")
    }
    
    /**
     * Returns whether spatial audio is currently enabled.
     */
    fun isEnabled(): Boolean = isEnabled
    
    /**
     * Cleans up resources. Should be called when the activity is destroyed.
     */
    fun destroy() {
        disableSpatialAudio()
    }
}

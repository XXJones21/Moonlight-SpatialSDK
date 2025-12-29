Dolby Atmos audio

Updated: Oct 9, 2025

Overview

Dolby Atmos
is an object-based audio technology designed to provide immersive audio experiences in cinemas, home theaters, and mobile devices. Use Dolby Atmos to create spatial audio that enhances immersion in your app.

Set up Dolby Atmos spatial audio

1. Detect device support

Determine if your device supports the Dolby Digital Plus (E-AC-3) audio format using the code in the Determining the Dolby decoding capabilities of the device section of Enabling Dolby Atmos in Android Mobile media apps

. AC-4 is not supported.

2. Play audio with ExoPlayer

Load the media you checked in the previous step into an ExoPlayer instance. If you want the ExoPlayer to be visible, it must included in a panel. If you’re new to ExoPlayer, see the official Getting started
guide.

3. Enable SpatialAudioFeature

In the registerFeatures() method of your app’s main activity file, register the spatial audio feature to connect the audio from ExoPlayer.

override fun registerFeatures(): List<SpatialFeature> {
  return listOf(VRFeature(this), SpatialAudioFeature())
}

4. Get audiosessionid when content is ready

In your main activity file, add this method to listen for player events
. It gets the audio session ID from ExoPlayer when the content is ready and connects the audio from ExoPlayer to the Spatial SDK panel entity.
Register your AudioSessionId with the SpatialAudioFeature using the registerAudioSessionId method.

override fun onPlaybackStateChanged(playbackState: Int) {
  if (playbackState == Player.STATE_READY) {
      val registeredAudioSessionId = 1
      val audioSessionId = player.audioSessionId
      spatialAudioFeature.registerAudioSessionId(registeredAudioSessionId, audioSessionId)
      Log.i("DOLBY_ATMOS", "AudioSessionId after ready=$audioSessionId")
      panelEntity.setComponent(AudioSessionId(registerAudioSessionId, AudioType.SOUNDFIELD))
  }
}

When you move around the scene or when the Entity emitting audio moves, the audio continues to emit from the Entity’s current location.

Audio types

The AudioSessionId component supports several audio types:

    AMBISONIC_ORDER_1: First-order ambisonic audio (four channels).
    AMBISONIC_ORDER_2: Second-order ambisonic audio (nine channels).
    AMBISONIC_ORDER_3: Third-order ambisonic audio (16 channels).
    MONO: Standard audio with a single channel.
    STEREO: Standard audio with left and right (LR) channels.

Using the AudioType.SOUNDFIELD enum works with any multichannel audio file. If you use ambisonic audio, play it using the other enum options.
Setting stereo audio offsets
Use the AudioSessionStereoOffsets component to set stereo audio offsets for the left and right channels, each represented by a Vector3.
The AudioSessionStereoOffsets component has two different modes.

    The WORLD_SPACE mode positions stereo offsets in absolute world space. The offsets do not take into account the Entity’s position.
    The LOCAL_SPACE mode positions stereo offsets relative to the Entity’s local space. If the Entity has no Transform, the offsets are positioned in world space.

This example builds on the basic audio setup by adding stereo channel positioning. Notice two key differences: the AudioType is set to STEREO instead of SOUNDFIELD, and the AudioSessionStereoOffsets component positions the left and right channels in 3D space.

override fun onPlaybackStateChanged(playbackState: Int) {
  if (playbackState == Player.STATE_READY) {
      val registeredAudioSessionId = 1
      val audioSessionId = player.audioSessionId
      spatialAudioFeature.registerAudioSessionId(registeredAudioSessionId, audioSessionId)
      Log.i("DOLBY_ATMOS", "AudioSessionId after ready=$audioSessionId")
      panelEntity.setComponent(AudioSessionId(registerAudioSessionId, AudioType.STEREO))
      panelEntity.setComponent(AudioSessionStereoOffsets(left = Vector3(-1f, 0f, 0f), right = Vector3(1f, 0f, 0f))) // offsets based on the user looking at the panel in the +z direction
  }
}

The Vector3(-1f, 0f, 0f) and Vector3(1f, 0f, 0f) values position the left and right audio channels one unit apart on the X-axis, creating proper stereo separation when the user faces the panel.
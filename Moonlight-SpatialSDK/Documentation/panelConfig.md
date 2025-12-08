# Configuring media playback

Media playback is a key use case for Spatial SDK. Spatial SDK provides specialized panel registration classes for media content. These classes simplify creating high-performance video panels with proper surface management, stereo modes, and DRM support. This guide covers the media-specific panel registration APIs and configuring different playback types.

## Media panel registration overview

Spatial SDK offers two specialized panel registration classes for media content:

    VideoSurfacePanelRegistration: Direct-to-surface rendering for maximum performance and DRM support
    ReadableVideoSurfacePanelRegistration: Surface rendering with post-processing capabilities for custom effects

These classes automatically handle surface management, media source integration, and performance optimization. They provide APIs for connecting media players like ExoPlayer.

### Choosing the right panel registration

Choose between VideoSurfacePanelRegistration and ReadableVideoSurfacePanelRegistration based on your performance requirements and whether you need post-processing capabilities.

#### Maximum performance and DRM support

Use VideoSurfacePanelRegistration for maximum performance and DRM support. This renders media content directly to the panel surface, bypassing the Android View system:

    Best for: High-resolution video, DRM content, 360° content, performance-critical scenarios
    Limitations: Cannot apply custom shaders or visual effects
    Surface management: Automatic - surface is provided to your media player
    Settings: Uses MediaPanelSettings with MediaPanelRenderOptions

VideoSurfacePanelRegistration(
    R.id.video_panel,
    surfaceConsumer = { entity, surface ->
        // ExoPlayer setup
        val exoPlayer = ExoPlayer.Builder(this).build().apply {
            setVideoSurface(surface)
            setMediaItem(mediaItem)
            prepare()
        }
    },
    settingsCreator = { entity ->
        MediaPanelSettings(
            shape = QuadShapeOptions(width = 1.6f, height = 0.9f),
            display = PixelDisplayOptions(width = 1920, height = 1080),
            rendering = MediaPanelRenderOptions(
                stereoMode = StereoMode.LeftRight,
                isDRM = true
            )
        )
    }
)

#### Media rendering and post-processing capabilities

Use ReadableVideoSurfacePanelRegistration when you need both media rendering and post-processing capabilities:

    Best for: Content requiring custom shaders, visual effects, or panel content analysis
    Trade-off: Lower performance than direct-to-surface but more flexible
    Surface management: Creates a SurfaceView within a standard Android layout
    Settings: Uses ReadableMediaPanelSettings with ReadableMediaPanelRenderOptions

Known issue: ReadableMediaPanelRenderOptions with PanelRenderMode.Mesh() incorrectly creates a compositor layer. See Known issues for details and workarounds.

ReadableVideoSurfacePanelRegistration(
    R.id.readable_video_panel,
    surfaceConsumer = { entity, surface ->
        // ExoPlayer setup with surface
        exoPlayer.setVideoSurface(surface)
    },
    settingsCreator = { entity ->
        ReadableMediaPanelSettings(
            shape = QuadShapeOptions(width = 1.6f, height = 0.9f),
            display = PixelDisplayOptions(width = 1920, height = 1080),
            rendering = ReadableMediaPanelRenderOptions(
                mips = 4, // Enable mipmapping for distance viewing
                stereoMode = StereoMode.UpDown
            )
        )
    }
)

## Video types and shapes

Both panel registration types support various video formats through their shape configuration:

### Rectilinear (Standard flat video)
Standard rectangular video content uses QuadShapeOptions:

MediaPanelSettings(
    shape = QuadShapeOptions(width = 1.6f, height = 0.9f), // 16:9 aspect ratio
    display = PixelDisplayOptions(width = 1920, height = 1080),
    // ...
)

### 180° video

Semi-cylindrical immersive content uses Equirect180ShapeOptions:

MediaPanelSettings(
    shape = Equirect180ShapeOptions(radius = 50.0f),
    display = PixelDisplayOptions(width = 3840, height = 1080),
    // ...
)

### 360° video

Full spherical immersive content uses Equirect360ShapeOptions:

MediaPanelSettings(
    shape = Equirect360ShapeOptions(radius = 300.0f),
    display = PixelDisplayOptions(width = 3840, height = 1920),
    rendering = MediaPanelRenderOptions(
        stereoMode = StereoMode.UpDown,
        zIndex = -1 // Render behind other panels
    )
)

## Monoscopic and stereoscopic media

Both media panel registration types support stereo content through the stereoMode property in their rendering options:

### Monoscopic

Monoscopic content displays the same image to both eyes. Configure this in the rendering options:

// For VideoSurfacePanelRegistration
MediaPanelSettings(
    // ... shape and display options
    rendering = MediaPanelRenderOptions(
        stereoMode = StereoMode.None // Default monoscopic behavior
    )
)

// For ReadableVideoSurfacePanelRegistration
ReadableMediaPanelSettings(
    // ... shape and display options
    rendering = ReadableMediaPanelRenderOptions(
        stereoMode = StereoMode.MonoLeft // Use left half for both eyes
    )
)

Available monoscopic modes:

    StereoMode.None: Displays the entire texture to both eyes (default)
    StereoMode.MonoLeft: Displays the left half of the texture in both eyes
    StereoMode.MonoUp: Displays the top half of the texture in both eyes

### Stereoscopic

Stereoscopic content provides depth perception by showing different images to each eye:

MediaPanelSettings(
    shape = QuadShapeOptions(width = 1.6f, height = 0.9f),
    display = PixelDisplayOptions(width = 3840, height = 1080), // Side-by-side stereo resolution
    rendering = MediaPanelRenderOptions(
        stereoMode = StereoMode.LeftRight // Left-right stereo content
    )
)

Available stereoscopic modes:

    StereoMode.LeftRight: Left half to left eye, right half to right eye (common for side-by-side stereo)
    StereoMode.UpDown: Top half to left eye, bottom half to right eye (common for over/under stereo)

## Best practices for media playback

### Resolution matching

Match your panel resolution to your video content for optimal quality and performance:

// For 1080p video content
MediaPanelSettings(
    display = PixelDisplayOptions(width = 1920, height = 1080),
    // ...
)

### DRM content considerations

Use VideoSurfacePanelRegistration for DRM-protected content. It provides the secure rendering pipeline required:

VideoSurfacePanelRegistration(
    R.id.drm_video_panel,
    surfaceConsumer = { entity, surface ->
        // DRM-enabled ExoPlayer setup
        exoPlayer.setVideoSurface(surface)
    },
    settingsCreator = {
        MediaPanelSettings(
            // ... shape and display
            rendering = MediaPanelRenderOptions(isDRM = true)
        )
    }
)

###Panel positioning for immersive content

Position 360° content behind other panels to create proper layering:

MediaPanelSettings(
    shape = Equirect360ShapeOptions(radius = 300.0f),
    // ...
    rendering = MediaPanelRenderOptions(
        zIndex = -1, // Render behind UI panels
        stereoMode = StereoMode.UpDown
    )
)

See 2D Panel Config and Resolution for detailed resolution guidance.

# (Advanced) Directly control panel config with PanelConfigOptions

Advanced usage: This section is for developers who need direct control over panel configuration beyond what the media-specific Panel Settings APIs provide. Most developers should use VideoSurfacePanelRegistration or ReadableVideoSurfacePanelRegistration instead.

If you’re migrating from an older codebase or need custom panel behavior not covered by the media panel registrations, you can use PanelConfigOptions directly. However, this requires manual management of surfaces, performance optimizations, and media integration.

## Direct-to-compositor rendering
Direct-to-compositor rendering improves performance by avoiding image copying in memory. To enable it for your panel manually you must set these properties:

    Disable mipmapping: mips = 1
        Spatial SDK generates mipmaps to prevent panels from appearing pixelated at a distance. Disabling mipmap generation can improve performance because the panel content is consumed directly from the compositor without changes.
    Disable SceneTexture: forceSceneTexture = false
        A scene texture applies effects such as partial transparency and shaders onto the panel.
    Disable transparency: enableTransparent = false
        enableTransparent is false by default. If set to enableTransparent = true, the sceneTexture will be forced on and your app will crash.

MediaPanelSettings sets these properties automatically. Here’s an example of manually setting these properties:

PanelCreator(
    registrationId = R.id.my_media_panel,
    panelCreator = { entity ->
        val panelConfigOptions = PanelConfigOptions().apply {
            // Panel shape and size
            layoutWidthInPx = 1920
            layoutHeightInPx = 1080
            width = 1.6f
            height = 0.9f

            // Enable direct-to-compositor
            mips = 1 // Disable mipmapping
            forceSceneTexture = false // Disable scene texture
            enableTransparent = false // Disable transparency
        }

        PanelSceneObject(scene, spatialContext, R.layout.surface_layout, entity, panelConfigOptions)
    }
)

## Direct-to-surface rendering

Direct-to-surface rendering renders directly to a panel surface. Direct-to-surface leads to performance improvements because the panel creation process doesn’t set up an Android UI. Direct-to-compositor is a prerequisite for direct-to-surface rendering. If you try to enable direct-to-surface without direct-to-compositor, your app will crash.

VideoSurfacePanelRegistration sets this up automatically. The following code sample shows how to set it up manually if you need more direct control.

The following code sample:

    Configures direct-to-compositor prerequisites (mips=1, forceSceneTexture=false).
    Creates a PanelSceneObject and attaches it to the entity.
    Sets up ExoPlayer with the media content.
    Connects ExoPlayer directly to the panel surface with setVideoSurface().

private fun createDirectToSurfacePanel(
    panelConfigBlock: PanelConfigOptions.() -> Unit,
    mediaItem: MediaItem,
) {
    // Create entity
    entity = Entity.create(
        Transform(),
        Hittable(hittable = MeshCollision.LineTest),
    )

    val panelConfigOptions = PanelConfigOptions().apply(panelConfigBlock)

    // Enable Direct-To-Compositor prerequisites
    panelConfigOptions.apply {
        mips = 1
        forceSceneTexture = false
        enableTransparent = false
    }

    SpatialActivityManager.executeOnVrActivity<AppSystemActivity> { immersiveActivity ->
        // Create PanelSceneObject with custom configs
        val panelSceneObject = PanelSceneObject(immersiveActivity.scene, entity, panelConfigOptions)

        // Assign PanelSceneObject to entity
        immersiveActivity.systemManager
            .findSystem<SceneObjectSystem>()
            .addSceneObject(
                entity, CompletableFuture<SceneObject>().apply { complete(panelSceneObject) })

        // Set up ExoPlayer
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()

        // Connect ExoPlayer directly to panel surface
        exoPlayer.setVideoSurface(panelSceneObject.getSurface())
    }
}

This approach bypasses the Android UI rendering pipeline. It improves performance by allowing ExoPlayer to render video frames directly to the panel surface.

### Migration recommendation

If you’re currently using PanelConfigOptions for media content, consider migrating to the media-specific panel registrations:

// Old approach with PanelConfigOptions
val panelConfigOptions = PanelConfigOptions().apply {
    layoutWidthInPx = 1920
    layoutHeightInPx = 1080
    width = 1.6f
    height = 0.9f
    mips = 1
    forceSceneTexture = false
    stereoMode = StereoMode.LEFT_RIGHT
}

// New approach with VideoSurfacePanelRegistration
VideoSurfacePanelRegistration(
    R.id.video_panel,
    surfaceConsumer = { entity, surface -> exoPlayer.setVideoSurface(surface) },
    settingsCreator = { entity ->
        MediaPanelSettings(
            shape = QuadShapeOptions(width = 1.6f, height = 0.9f),
            display = PixelDisplayOptions(width = 1920, height = 1080),
            rendering = MediaPanelRenderOptions(stereoMode = StereoMode.LeftRight)
        )
    }
)

The new media panel registrations handle surface management, performance optimizations, and ExoPlayer integration automatically. This makes your code more maintainable and less error-prone.
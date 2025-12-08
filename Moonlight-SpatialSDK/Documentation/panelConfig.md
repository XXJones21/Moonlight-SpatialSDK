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
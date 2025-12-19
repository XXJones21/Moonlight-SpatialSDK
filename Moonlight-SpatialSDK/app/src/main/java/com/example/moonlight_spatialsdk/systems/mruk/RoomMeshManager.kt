package com.example.moonlight_spatialsdk.systems.mruk

import android.util.Log
import com.meta.spatial.core.Color4
import com.meta.spatial.mruk.AnchorProceduralMesh
import com.meta.spatial.mruk.AnchorProceduralMeshConfig
import com.meta.spatial.mruk.MRUKAnchor
import com.meta.spatial.mruk.MRUKFeature
import com.meta.spatial.mruk.MRUKLabel
import com.meta.spatial.mruk.MRUKLoadDeviceResult
import com.meta.spatial.toolkit.Material
import com.meta.spatial.toolkit.Transform

/**
 * Manages MRUK room mesh visualization for spatial audio and visual feedback.
 * 
 * Creates procedural meshes for walls, floor, and ceiling surfaces detected by the
 * Mixed Reality Utility Kit (MRUK). These meshes provide visual context for the room
 * environment and enable spatial audio propagation.
 * 
 * This implementation follows the Valinor pattern:
 * 1. Load scene data from device first
 * 2. Create AnchorProceduralMesh AFTER scene loads successfully
 * 
 * Usage:
 * 1. Call loadSceneFromDevice() to request scene data from the device
 * 2. AnchorProceduralMesh is created automatically after scene loads
 * 3. Call hideRoomMesh() / showRoomMesh() to toggle visibility
 * 4. Call destroy() to clean up resources
 */
class RoomMeshManager(
    private val mrukFeature: MRUKFeature,
) {
    companion object {
        private const val TAG = "RoomMeshManager"
    }
    
    private var procMeshSpawner: AnchorProceduralMesh? = null
    private var isSceneLoaded = false
    private var isMeshVisible = false
    
    /**
     * Loads the MRUK scene from the device.
     * 
     * This must be called after the USE_SCENE permission has been granted.
     * Following the Valinor pattern: load scene first, then create AnchorProceduralMesh
     * after the scene has loaded successfully.
     * 
     * @param onSceneLoaded Callback invoked when scene loading completes successfully
     * @param onSceneLoadFailed Callback invoked if scene loading fails
     */
    fun loadSceneFromDevice(
        onSceneLoaded: (() -> Unit)? = null,
        onSceneLoadFailed: ((MRUKLoadDeviceResult) -> Unit)? = null
    ) {
        if (isSceneLoaded) {
            Log.d(TAG, "Scene already loaded, skipping load request")
            onSceneLoaded?.invoke()
            return
        }
        
        Log.i(TAG, "Loading MRUK scene from device...")
        mrukFeature.loadSceneFromDevice().whenComplete { result, exception ->
            if (exception != null) {
                Log.e(TAG, "Exception loading scene from device", exception)
                if (result != null) {
                    onSceneLoadFailed?.invoke(result)
                }
                return@whenComplete
            }
            
            if (result != MRUKLoadDeviceResult.SUCCESS) {
                Log.e(TAG, "Error loading scene from device: $result")
                onSceneLoadFailed?.invoke(result)
                return@whenComplete
            }
            
            Log.i(TAG, "MRUK scene loaded successfully")
            isSceneLoaded = true
            
            // Log the room info for debugging
            val room = mrukFeature.getCurrentRoom()
            if (room != null) {
                Log.i(TAG, "Current room has ${room.anchors.size} anchors")
                logAnchorDetails(room.anchors)
                
                // Create AnchorProceduralMesh AFTER scene loads (Valinor pattern)
                initializeMeshVisualization()
            } else {
                Log.w(TAG, "No current room found after scene load")
            }
            
            onSceneLoaded?.invoke()
        }
    }
    
    /**
     * Logs details about each anchor for debugging coordinate system issues.
     */
    private fun logAnchorDetails(anchors: List<com.meta.spatial.core.Entity>) {
        anchors.forEachIndexed { index, anchorEntity ->
            val anchor = anchorEntity.tryGetComponent<MRUKAnchor>()
            val transform = anchorEntity.tryGetComponent<Transform>()
            
            if (anchor != null && transform != null) {
                val pos = transform.transform.t
                val forward = transform.transform.forward()
                Log.i(TAG, "Anchor #$index: Labels=${anchor.labels}, " +
                        "Pos=(${pos.x}, ${pos.y}, ${pos.z}), " +
                        "Forward=(${forward.x}, ${forward.y}, ${forward.z})")
            }
        }
    }
    
    /**
     * Initializes the AnchorProceduralMesh after scene data is loaded.
     * 
     * Following the Valinor pattern: create AnchorProceduralMesh AFTER scene loads.
     * This ensures the mesh spawner has access to the loaded anchor data.
     */
    private fun initializeMeshVisualization() {
        // Destroy existing spawner if present
        procMeshSpawner?.destroy()
        
        Log.i(TAG, "Creating AnchorProceduralMesh after scene load (Valinor pattern)...")
        
        // Create semi-transparent materials for room surfaces
        // Using unlit materials with alpha for visibility
        val wallMaterial = Material().apply {
            baseColor = Color4(0.2f, 0.4f, 0.8f, 0.15f) // Light blue, semi-transparent
            unlit = true
        }
        
        val floorMaterial = Material().apply {
            baseColor = Color4(0.2f, 0.6f, 0.3f, 0.1f) // Light green, semi-transparent
            unlit = true
        }
        
        val ceilingMaterial = Material().apply {
            baseColor = Color4(0.3f, 0.3f, 0.5f, 0.1f) // Light purple, semi-transparent
            unlit = true
        }
        
        // Create AnchorProceduralMesh AFTER scene is loaded
        // The collider parameter (second boolean) enables physics colliders
        procMeshSpawner = AnchorProceduralMesh(
            mrukFeature,
            mapOf(
                MRUKLabel.WALL_FACE to AnchorProceduralMeshConfig(wallMaterial, true),
                MRUKLabel.FLOOR to AnchorProceduralMeshConfig(floorMaterial, true),
                MRUKLabel.CEILING to AnchorProceduralMeshConfig(ceilingMaterial, true),
            ),
        )
        
        isMeshVisible = true
        Log.i(TAG, "AnchorProceduralMesh created with wall/floor/ceiling materials")
    }
    
    /**
     * Shows the room mesh visualization.
     * 
     * Recreates the AnchorProceduralMesh if it was previously destroyed.
     */
    fun showRoomMesh() {
        if (isMeshVisible && procMeshSpawner != null) {
            Log.d(TAG, "Room mesh already visible")
            return
        }
        
        if (!isSceneLoaded) {
            Log.w(TAG, "Cannot show room mesh - scene not loaded yet")
            return
        }
        
        Log.i(TAG, "Showing room mesh visualization...")
        initializeMeshVisualization()
        Log.i(TAG, "Room mesh visualization enabled")
    }
    
    /**
     * Hides and destroys the room mesh visualization.
     * 
     * Destroys the AnchorProceduralMesh spawner and all created meshes.
     */
    fun hideRoomMesh() {
        if (procMeshSpawner == null) {
            Log.d(TAG, "Room mesh already hidden")
            return
        }
        
        Log.i(TAG, "Hiding room mesh visualization...")
        procMeshSpawner?.destroy()
        procMeshSpawner = null
        isMeshVisible = false
        Log.i(TAG, "Room mesh visualization destroyed")
    }
    
    /**
     * Returns whether the room mesh is currently visible.
     */
    fun isMeshVisible(): Boolean = isMeshVisible
    
    /**
     * Returns whether the MRUK scene has been loaded.
     */
    fun isSceneLoaded(): Boolean = isSceneLoaded
    
    /**
     * Cleans up all resources.
     * 
     * Should be called when the activity is being destroyed.
     */
    fun destroy() {
        hideRoomMesh()
        isSceneLoaded = false
    }
}

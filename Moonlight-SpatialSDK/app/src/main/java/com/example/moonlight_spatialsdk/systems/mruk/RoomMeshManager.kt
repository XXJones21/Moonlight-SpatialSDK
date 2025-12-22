package com.example.moonlight_spatialsdk.systems.mruk

import android.util.Log
import com.meta.spatial.mruk.AnchorProceduralMesh
import com.meta.spatial.mruk.AnchorProceduralMeshConfig
import com.meta.spatial.mruk.MRUKFeature
import com.meta.spatial.mruk.MRUKLabel
import com.meta.spatial.mruk.MRUKLoadDeviceResult

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
                
                // Create AnchorProceduralMesh AFTER scene loads
                initializeMeshColliders()
            } else {
                Log.w(TAG, "No current room found after scene load")
            }
            
            onSceneLoaded?.invoke()
        }
    }
    
    /**
     * Initializes the AnchorProceduralMesh with colliders only (no visible materials).
     * 
     * Uses null materials like MRUKSample - creates invisible collision geometry
     * for physics/raycasting without visual rendering.
     */
    private fun initializeMeshColliders() {
        procMeshSpawner?.destroy()
        
        Log.i(TAG, "Creating AnchorProceduralMesh with colliders only (no visualization)...")
        
        // Use null materials - creates invisible colliders only (MRUKSample pattern)
        procMeshSpawner = AnchorProceduralMesh(
            mrukFeature,
            mapOf(
                MRUKLabel.WALL_FACE to AnchorProceduralMeshConfig(null, true),
                MRUKLabel.FLOOR to AnchorProceduralMeshConfig(null, true),
                MRUKLabel.CEILING to AnchorProceduralMeshConfig(null, true),
            ),
        )
        
        isMeshVisible = true
        Log.i(TAG, "AnchorProceduralMesh created with colliders only")
    }
    
    /**
     * Shows the room mesh colliders.
     * 
     * Recreates the AnchorProceduralMesh if it was previously destroyed.
     */
    fun showRoomMesh() {
        if (isMeshVisible && procMeshSpawner != null) {
            Log.d(TAG, "Room mesh colliders already active")
            return
        }
        
        if (!isSceneLoaded) {
            Log.w(TAG, "Cannot show room mesh - scene not loaded yet")
            return
        }
        
        Log.i(TAG, "Enabling room mesh colliders...")
        initializeMeshColliders()
        Log.i(TAG, "Room mesh colliders enabled")
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

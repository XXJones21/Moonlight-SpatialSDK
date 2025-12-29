package com.example.moonlight_spatialsdk.entities

import android.net.Uri
import android.util.Log
import com.example.moonlight_spatialsdk.systems.heroLighting.HeroLightingSystem
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector2
import com.meta.spatial.core.Vector3
import com.meta.spatial.core.Vector4
import com.meta.spatial.runtime.BlendMode
import com.meta.spatial.runtime.DepthTest
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneMaterialAttribute
import com.meta.spatial.runtime.SceneMaterialDataType
import com.meta.spatial.runtime.SceneMesh
import com.meta.spatial.runtime.SceneTexture
import com.meta.spatial.runtime.SortOrder
import com.meta.spatial.runtime.StereoMode
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.Hittable
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.PanelDimensions
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.SpatialActivityManager
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.TransformParent
import com.meta.spatial.toolkit.Visible

/**
 * Creates a bias lighting effect around a video panel.
 * 
 * Uses a single quad mesh behind the panel with a shader that computes
 * a ring shape mathematically. The center (panel area) is transparent,
 * and the outer ring samples video edge colors with distance-based falloff.
 */
class BiasLightingEntity(
    private val heroLightingSystem: HeroLightingSystem?
) {
    companion object {
        private const val TAG = "BiasLightingEntity"
        private const val MESH_NAME = "mesh://BiasLighting_9slice"
        
        // DEBUG: Set to true to show falloff shape instead of video colors
        private const val DEBUG_MODE = false
        
        // How much the glow extends beyond panel edge (in meters)
        // ~8 inches = 0.2m for the falloff ring
        private const val GLOW_PADDING = 0.2f
        
        // Z offset behind the panel
        private const val Z_OFFSET = 0.01f
        
        // Video sampling mip level (higher = more blur)
        private const val DEFAULT_MIP_LEVEL = 4.0f
    }
    
    private var parentPanel: Entity? = null
    private var panelSize: Vector2 = Vector2(1f, 1f)
    
    private var glowEntity: Entity? = null
    private var glowMaterial: SceneMaterial? = null
    
    private var _isVisible = false
    val isVisible: Boolean get() = _isVisible
    
    private var _intensity = 0.8f
    
    init {
        registerMeshAndMaterial()
    }
    
    private fun registerMeshAndMaterial() {
        SpatialActivityManager.executeOnVrActivity<AppSystemActivity> { activity ->
            // Create the material
            val material = createBiasLightingMaterial(activity)
            glowMaterial = material
            
            // Register quad mesh creator
            activity.registerMeshCreator(MESH_NAME) {
                SceneMesh.quad(
                    Vector3(-0.5f, -0.5f, 0f),
                    Vector3(0.5f, 0.5f, 0f),
                    material
                )
            }
            
            Log.d(TAG, "Bias lighting mesh and material registered")
        }
    }
    
    /**
     * Registers the material with HeroLightingSystem to receive video texture.
     * Uses textureOnly=true to prevent HeroLightingSystem from overwriting
     * emissiveFactor and albedoFactor which the bias lighting shader needs.
     * Call this after the video panel and systems are ready.
     */
    fun registerWithLightingSystem() {
        val material = glowMaterial ?: return
        heroLightingSystem?.registerMaterial(material, custom = true, textureOnly = true)
        Log.d(TAG, "Material registered with HeroLightingSystem (texture-only)")
    }
    
    private fun createBiasLightingMaterial(activity: AppSystemActivity): SceneMaterial {
        val p3 = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.DISPLAY_P3)
        val blackColor = android.graphics.Color.valueOf(0f, 0f, 0f, 1f, p3)
        val whiteColor = android.graphics.Color.valueOf(1f, 1f, 1f, 1f, p3)
        val blackTexture = SceneTexture(blackColor)
        val whiteTexture = SceneTexture(whiteColor)
        
        return SceneMaterial.custom(
            "bias_lighting_9slice",
            arrayOf(
                // Uniforms (binding 0)
                SceneMaterialAttribute("emissiveFactor", SceneMaterialDataType.Vector4),
                SceneMaterialAttribute("albedoFactor", SceneMaterialDataType.Vector4),
                SceneMaterialAttribute("matParams", SceneMaterialDataType.Vector4),
                SceneMaterialAttribute("stereoParams", SceneMaterialDataType.Vector4),
                // Textures (bindings 1-4) - must match customBindingFrag.glsl
                SceneMaterialAttribute("albedoSampler", SceneMaterialDataType.Texture2D),
                SceneMaterialAttribute("roughnessMetallicTexture", SceneMaterialDataType.Texture2D),
                SceneMaterialAttribute("emissive", SceneMaterialDataType.Texture2D),
                SceneMaterialAttribute("occlusion", SceneMaterialDataType.Texture2D),
            ),
        ).apply {
            setBlendMode(BlendMode.ADDITIVE)
            setSortOrder(SortOrder.TRANSLUCENT)
            setDepthTest(DepthTest.LESS_OR_EQUAL)
            setStereoMode(StereoMode.None)
            
            val quadWidth = panelSize.x + GLOW_PADDING * 2
            val quadHeight = panelSize.y + GLOW_PADDING * 2
            
            // emissiveFactor: xy = quad size, zw = panel size
            setAttribute("emissiveFactor", Vector4(
                quadWidth,
                quadHeight,
                panelSize.x,
                panelSize.y
            ))
            
            // albedoFactor.x = glow padding in meters
            setAttribute("albedoFactor", Vector4(GLOW_PADDING, 0f, 0f, 0f))
            
            // matParams.x = intensity
            setAttribute("matParams", Vector4(_intensity, 0f, 0f, 0f))
            
            // stereoParams: x = unused, y = mipLevel, z = debugMode
            setAttribute("stereoParams", Vector4(
                0f,
                DEFAULT_MIP_LEVEL,
                if (DEBUG_MODE) 1f else 0f,
                0f
            ))
            
            // Textures
            setTexture("albedoSampler", whiteTexture)
            setTexture("roughnessMetallicTexture", blackTexture)
            setTexture("emissive", blackTexture)
            setTexture("occlusion", whiteTexture)
        }
    }
    
    /**
     * Attaches the bias lighting to a video panel.
     */
    fun attachToPanel(panel: Entity) {
        parentPanel = panel
        
        val dimensions = panel.tryGetComponent<PanelDimensions>()
        if (dimensions != null) {
            panelSize = dimensions.dimensions
        }
        
        createGlowEntity()
        updateGlowTransform()
        
        // Register with lighting system now that panel exists
        registerWithLightingSystem()
        
        Log.d(TAG, "Bias lighting attached to panel, size: $panelSize")
    }
    
    private fun createGlowEntity() {
        // Clear existing entity
        glowEntity?.destroy()
        
        val parent = parentPanel ?: return
        
        glowEntity = Entity.create(
            Mesh(Uri.parse(MESH_NAME)),
            Transform(Pose(Vector3(0f, 0f, Z_OFFSET))),
            TransformParent(parent),
            Hittable(MeshCollision.NoCollision),
            Scale(Vector3(1f)),
            Visible(_isVisible)
        )
        
        Log.d(TAG, "Created glow entity")
    }
    
    private fun updateGlowTransform() {
        val entity = glowEntity ?: return
        
        // Quad size includes padding for the glow to extend beyond panel
        val quadWidth = panelSize.x + GLOW_PADDING * 2
        val quadHeight = panelSize.y + GLOW_PADDING * 2
        
        entity.setComponent(Scale(Vector3(quadWidth, quadHeight, 1f)))
        
        // Update material uniforms
        // emissiveFactor: xy = quad size, zw = panel size
        glowMaterial?.setAttribute("emissiveFactor", Vector4(
            quadWidth,
            quadHeight,
            panelSize.x,
            panelSize.y
        ))
    }
    
    /**
     * Updates the bias lighting when panel size changes.
     */
    fun updatePanelSize(newSize: Vector2) {
        if (panelSize != newSize) {
            panelSize = newSize
            updateGlowTransform()
        }
    }
    
    /**
     * Updates the bias lighting based on parent panel's current scale.
     */
    fun updateFromParentScale() {
        val parent = parentPanel ?: return
        val dimensions = parent.tryGetComponent<PanelDimensions>() ?: return
        val scale = parent.tryGetComponent<Scale>()?.scale ?: Vector3(1f)
        
        val effectiveSize = Vector2(
            dimensions.dimensions.x * scale.x,
            dimensions.dimensions.y * scale.y
        )
        
        updatePanelSize(effectiveSize)
    }
    
    /**
     * Sets the visibility of the bias lighting effect.
     */
    fun setVisible(visible: Boolean) {
        _isVisible = visible
        glowEntity?.setComponent(Visible(visible))
        Log.d(TAG, "Bias lighting visibility set to: $visible")
    }
    
    /**
     * Sets the intensity of the bias lighting effect.
     */
    fun setIntensity(intensity: Float) {
        _intensity = intensity
        // matParams.x = intensity (HeroLightingSystem also updates this)
        glowMaterial?.setAttribute("matParams", Vector4(intensity, 0f, 0f, 0f))
    }
    
    /**
     * Cleans up all entities and resources.
     */
    fun destroy() {
        glowEntity?.destroy()
        glowEntity = null
        glowMaterial?.destroy()
        glowMaterial = null
        parentPanel = null
        Log.d(TAG, "Bias lighting destroyed")
    }
}

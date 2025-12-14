package com.example.moonlight_spatialsdk.systems

import com.meta.spatial.core.Query
import com.meta.spatial.core.SystemBase
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.ControllerType

/**
 * System that polls controller ButtonBits each frame to detect menu button presses.
 * Uses polling instead of event listeners to avoid conflicts with ControllerHandler
 * consuming events at the Activity level.
 */
class MenuButtonSystem(
    private val onMenuButtonPressed: () -> Unit
) : SystemBase() {
    
    private var lastProcessedButtons = 0
    
    override fun execute() {
        val controllersQ = Query.where { has(Controller.id) }
        val controllers = controllersQ.eval().filter { it.isLocal() }
        
        for (controllerEntity in controllers) {
            val controllerData = controllerEntity.getComponent<Controller>()
            
            // Only check active controllers
            if (!controllerData.isActive || controllerData.type != ControllerType.CONTROLLER) {
                continue
            }
            
            // Check for menu button press using ButtonBits
            // Menu button on Quest controllers - need to verify exact ButtonBits value
            // Using changedButtons to detect new press (not held state)
            val menuButtonBits = ButtonBits.Menu
            val menuButtonPressed = (controllerData.changedButtons and menuButtonBits) == menuButtonBits &&
                                   (controllerData.buttonState and menuButtonBits) == menuButtonBits
            
            // Only trigger on new press, not on held state
            if (menuButtonPressed && (lastProcessedButtons and menuButtonBits) == 0) {
                onMenuButtonPressed()
                lastProcessedButtons = lastProcessedButtons or menuButtonBits
            } else if ((controllerData.buttonState and menuButtonBits) == 0) {
                // Button released - clear from processed state
                lastProcessedButtons = lastProcessedButtons and menuButtonBits.inv()
            }
        }
    }
}

package com.example.moonlight_spatialsdk.panels.stereoDepthSlider

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meta.spatial.uiset.slider.SpatialSliderSmall
import com.meta.spatial.uiset.theme.SpatialTheme

/**
 * Composable for the horizontal stereo depth slider panel positioned above the video panel.
 * 
 * Uses SpatialSliderSmall for compact controls and fine-tuning.
 * Controls the depth factor (0.0 = flat 2D, 1.0 = maximum 3D depth).
 * Text is displayed above the slider.
 * 
 * @param depthFactor Current depth value (0.0 to 1.0)
 * @param onDepthChange Callback when slider value changes, receives new value and returns updated value
 */
@Composable
fun StereoDepthSliderCompose(
    depthFactor: Float = 0.5f,
    onDepthChange: (Float) -> Float
) {
    var currentValue by remember { mutableFloatStateOf(depthFactor) }
    
    // Update local state when external value changes
    if (depthFactor != currentValue) {
        currentValue = depthFactor
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        // Text above slider
        Text(
            text = "Depth: ${String.format("%.2f", currentValue)}",
            fontSize = 12.sp,
            color = SpatialTheme.colorScheme.primaryAlphaBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        
        // Slider below text
        SpatialSliderSmall(
            modifier = Modifier.fillMaxWidth(),
            value = currentValue,
            onChanged = { newValue ->
                currentValue = newValue
                val updatedValue = onDepthChange(newValue)
                currentValue = updatedValue
            },
        )
    }
}

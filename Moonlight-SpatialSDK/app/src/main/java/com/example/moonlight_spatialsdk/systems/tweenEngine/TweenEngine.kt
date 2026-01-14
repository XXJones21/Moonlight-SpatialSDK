package com.example.moonlight_spatialsdk.systems.tweenEngine

import kotlinx.coroutines.*
import kotlin.math.pow

/**
 * A simplified tween engine using Kotlin Coroutines.
 * 
 * This provides smooth value interpolation over time for animations and transitions.
 * Unlike external tween libraries, this uses native Kotlin coroutines for simplicity.
 * 
 * Usage:
 * ```
 * val scope = CoroutineScope(Dispatchers.Main)
 * TweenEngine.tween(
 *     scope = scope,
 *     startValue = 0f,
 *     endValue = 1f,
 *     durationMs = 500,
 *     easing = TweenEasing.EASE_OUT_QUAD
 * ) { value ->
 *     // Apply the animated value
 * }
 * ```
 */
object TweenEngine {
    
    /**
     * Starts a tween animation from startValue to endValue.
     * 
     * @param scope CoroutineScope to run the animation in
     * @param startValue Initial value
     * @param endValue Target value
     * @param durationMs Duration of the tween in milliseconds
     * @param easing Easing function to apply
     * @param onUpdate Callback invoked with the current interpolated value
     * @return Job that can be cancelled to stop the animation
     */
    fun tween(
        scope: CoroutineScope,
        startValue: Float,
        endValue: Float,
        durationMs: Long,
        easing: TweenEasing = TweenEasing.LINEAR,
        onUpdate: (Float) -> Unit
    ): Job {
        return scope.launch {
            val startTime = System.currentTimeMillis()
            var elapsed: Long
            
            do {
                elapsed = System.currentTimeMillis() - startTime
                val progress = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
                val easedProgress = applyEasing(progress, easing)
                val currentValue = startValue + (endValue - startValue) * easedProgress
                
                onUpdate(currentValue)
                
                if (elapsed < durationMs) {
                    delay(16) // ~60 FPS
                }
            } while (elapsed < durationMs)
            
            // Ensure we end exactly at the target value
            onUpdate(endValue)
        }
    }
    
    /**
     * Tweens multiple values simultaneously.
     * 
     * @param scope CoroutineScope to run the animation in
     * @param startValues Array of initial values
     * @param endValues Array of target values
     * @param durationMs Duration of the tween in milliseconds
     * @param easing Easing function to apply
     * @param onUpdate Callback invoked with the current interpolated values
     * @return Job that can be cancelled to stop the animation
     */
    fun tweenMultiple(
        scope: CoroutineScope,
        startValues: FloatArray,
        endValues: FloatArray,
        durationMs: Long,
        easing: TweenEasing = TweenEasing.LINEAR,
        onUpdate: (FloatArray) -> Unit
    ): Job {
        require(startValues.size == endValues.size) { "Start and end value arrays must be same size" }
        
        return scope.launch {
            val startTime = System.currentTimeMillis()
            val currentValues = FloatArray(startValues.size)
            var elapsed: Long
            
            do {
                elapsed = System.currentTimeMillis() - startTime
                val progress = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
                val easedProgress = applyEasing(progress, easing)
                
                for (i in startValues.indices) {
                    currentValues[i] = startValues[i] + (endValues[i] - startValues[i]) * easedProgress
                }
                
                onUpdate(currentValues)
                
                if (elapsed < durationMs) {
                    delay(16) // ~60 FPS
                }
            } while (elapsed < durationMs)
            
            // Ensure we end exactly at the target values
            System.arraycopy(endValues, 0, currentValues, 0, endValues.size)
            onUpdate(currentValues)
        }
    }
    
    private fun applyEasing(t: Float, easing: TweenEasing): Float {
        return when (easing) {
            TweenEasing.LINEAR -> t
            TweenEasing.EASE_IN_QUAD -> t * t
            TweenEasing.EASE_OUT_QUAD -> t * (2 - t)
            TweenEasing.EASE_IN_OUT_QUAD -> if (t < 0.5f) 2 * t * t else -1 + (4 - 2 * t) * t
            TweenEasing.EASE_IN_CUBIC -> t * t * t
            TweenEasing.EASE_OUT_CUBIC -> (t - 1).pow(3) + 1
            TweenEasing.EASE_IN_OUT_CUBIC -> if (t < 0.5f) 4 * t * t * t else (t - 1) * (2 * t - 2) * (2 * t - 2) + 1
            TweenEasing.EASE_IN_EXPO -> if (t == 0f) 0f else 2f.pow(10 * (t - 1))
            TweenEasing.EASE_OUT_EXPO -> if (t == 1f) 1f else 1 - 2f.pow(-10 * t)
        }
    }
}

/**
 * Easing functions for smooth animations.
 */
enum class TweenEasing {
    /** Constant speed */
    LINEAR,
    /** Slow start, fast end */
    EASE_IN_QUAD,
    /** Fast start, slow end */
    EASE_OUT_QUAD,
    /** Slow start and end */
    EASE_IN_OUT_QUAD,
    /** Slow start (cubic) */
    EASE_IN_CUBIC,
    /** Fast start, slow end (cubic) */
    EASE_OUT_CUBIC,
    /** Slow start and end (cubic) */
    EASE_IN_OUT_CUBIC,
    /** Very slow start (exponential) */
    EASE_IN_EXPO,
    /** Very fast start, slow end (exponential) */
    EASE_OUT_EXPO
}

/**
 * A mutable float wrapper for use with tween animations.
 * 
 * This allows tracking a value that changes over time via tweening.
 */
class TweenFloat(var value: Float) {
    private var tweenJob: Job? = null
    
    /**
     * Animates this float to the target value.
     * 
     * @param scope CoroutineScope to run the animation in
     * @param targetValue Value to animate to
     * @param durationMs Animation duration in milliseconds
     * @param easing Easing function to use
     * @param onUpdate Optional callback invoked each frame with the current value
     */
    fun animateTo(
        scope: CoroutineScope,
        targetValue: Float,
        durationMs: Long,
        easing: TweenEasing = TweenEasing.EASE_OUT_QUAD,
        onUpdate: ((Float) -> Unit)? = null
    ): Job {
        // Cancel any existing animation
        tweenJob?.cancel()
        
        val startValue = value
        tweenJob = TweenEngine.tween(
            scope = scope,
            startValue = startValue,
            endValue = targetValue,
            durationMs = durationMs,
            easing = easing
        ) { currentValue ->
            value = currentValue
            onUpdate?.invoke(currentValue)
        }
        
        return tweenJob!!
    }
    
    /**
     * Cancels any running animation.
     */
    fun cancelAnimation() {
        tweenJob?.cancel()
        tweenJob = null
    }
}


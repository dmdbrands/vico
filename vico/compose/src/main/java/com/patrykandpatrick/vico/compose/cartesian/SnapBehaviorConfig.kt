/*
 * Copyright 2024 by Patryk Goworowski and Patrick Michalik.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.patrykandpatrick.vico.compose.cartesian

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearOutSlowInEasing

/**
 * Configuration class for snap behavior that groups all snap-related settings.
 * Provides sensible defaults while allowing customization of window movement, animation, and snap function.
 *
 * Example usage:
 * ```kotlin
 * val scrollState = rememberVicoScrollState(
 *   snapBehaviorConfig = SnapBehaviorConfig(
 *     snapToLabelFunction = { currentXLabel, isDrag, isForward ->
 *       // Custom snap logic
 *       if (isDrag) currentXLabel?.roundToInt()?.toDouble() ?: 0.0
 *       else if (isForward) ceil(currentXLabel ?: 0.0)
 *       else floor(currentXLabel ?: 0.0)
 *     },
 *     velocityThresholds = SnapBehaviorConfig.VelocityThresholds(
 *       lowFling = 2000f,
 *       mediumFling = 15000f,
 *       highFling = 25000f
 *     ),
 *     windowMovement = SnapBehaviorConfig.WindowMovement(
 *       highFlingWindows = 3.0,
 *       mediumFlingWindows = 1.5,
 *       lowFlingWindows = 0.5
 *     ),
 *     animation = SnapBehaviorConfig.SnapAnimation(
 *       decayFrictionMultiplier = 0.05f,
 *       snapDurationMillis = 1500,
 *       snapEasing = FastOutSlowInEasing
 *     )
 *   )
 * )
 * ```
 */
public data class SnapBehaviorConfig(
    /**
     * Function that determines which X label to snap to.
     * @param currentXLabel The current X label value (center of visible range)
     * @param isDrag Whether this is a drag (low velocity) or fling (high velocity) operation
     * @param isForward Whether the scroll direction is forward (positive velocity)
     * @return The snapped X label value
     */
    val snapToLabelFunction: ((Double?, Boolean, Boolean) -> Double)? = null,

    /**
     * Velocity thresholds for determining window movement behavior (px/s).
     */
    val velocityThresholds: VelocityThresholds = VelocityThresholds(),

    /**
     * Window movement configuration for different velocity ranges.
     */
    val windowMovement: WindowMovement = WindowMovement(),

    /**
     * Animation configuration for snap behavior.
     */
    val animation: SnapAnimation = SnapAnimation()
) {
    /**
     * Velocity thresholds for snap behavior (px/s).
     */
    public data class VelocityThresholds(
        val lowFling: Float = 1500f,
        val mediumFling: Float = 12000f,
        val highFling: Float = 22000f,
        val velocityChangeThreshold: Float = 1000f, // Minimum velocity change to consider new fling
        val timeGapThreshold: Long = 100L // 100ms gap threshold
    )

    /**
     * Window movement configuration for different velocity ranges.
     */
    public data class WindowMovement(
        val highFlingWindows: Double = 2.0,
        val mediumFlingWindows: Double = 1.0,
        val lowFlingWindows: Double = 0.01,
        val dragWindows: Double = 0.0
    )

    /**
     * Animation configuration for snap behavior.
     */
    public data class SnapAnimation(
        val decayFrictionMultiplier: Float = 0.1f, // Very slow, ultra-smooth deceleration
        val snapDurationMillis: Int = 1200, // Much slower snap for ultra-smooth transition
        val snapEasing: Easing = LinearOutSlowInEasing // Linear easing for consistent smoothness
    )
}


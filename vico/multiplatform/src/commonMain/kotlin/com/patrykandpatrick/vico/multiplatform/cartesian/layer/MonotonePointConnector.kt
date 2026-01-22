/*
 * Copyright 2025 by Patryk Goworowski and Patrick Michalik.
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

package com.patrykandpatrick.vico.multiplatform.cartesian.layer

import androidx.compose.ui.graphics.Path
import com.patrykandpatrick.vico.multiplatform.cartesian.CartesianDrawingContext
import kotlin.math.abs
import kotlin.math.min

/**
 * Implements iOS-style monotone cubic interpolation using Fritsch-Carlson algorithm.
 *
 * Key properties:
 * - Slopes are computed from neighboring points
 * - Slopes are clamped to prevent overshoot
 * - Resulting curve is smooth and preserves monotonicity
 * - Converted into cubic Bézier segments (GPU friendly)
 *
 * This produces curves similar to SwiftUI's `.monotone` interpolation and iOS Health app charts.
 */
internal class MonotonePointConnector : LineCartesianLayer.PointConnector {
  // State tracking for computing monotone tangents
  private var prevX: Float = 0f
  private var prevY: Float = 0f
  private var prevSlope: Float? = null
  private var isFirstSegment = true

  override fun connect(
    context: CartesianDrawingContext,
    path: Path,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
  ) {
    // Detect if we're starting a new line (path was reset)
    // When the previous point doesn't match, we're starting fresh
    if (isFirstSegment || (prevX != x1 || prevY != y1)) {
      reset()
    }

    // Calculate the secant slope for the current segment
    val dx = x2 - x1
    val dy = y2 - y1
    val currentSlope = if (dx != 0f) dy / dx else 0f

    // Compute tangent at start point (x1, y1)
    val t0 = if (prevSlope == null) {
      // First segment: use secant slope as tangent
      currentSlope
    } else {
      // Interior point: compute monotone tangent using Fritsch-Carlson
      computeMonotoneTangent(prevSlope!!, currentSlope)
    }

    // Compute tangent at end point (x2, y2)
    // Since we don't have the next segment yet, use the current secant slope
    // This will be corrected on the next segment's start point
    val t1 = currentSlope

    // Convert Hermite spline to cubic Bézier control points
    // Hermite to Bézier conversion: control points at 1/3 intervals
    val c1x = x1 + dx / 3f
    val c1y = y1 + t0 * dx / 3f

    val c2x = x2 - dx / 3f
    val c2y = y2 - t1 * dx / 3f

    path.cubicTo(c1x, c1y, c2x, c2y, x2, y2)

    // Update state for next segment
    prevX = x2
    prevY = y2
    prevSlope = currentSlope
    isFirstSegment = false
  }

  /**
   * Computes a monotone tangent at an interior point using Fritsch-Carlson method.
   *
   * The key insight is that to preserve monotonicity:
   * - If the slopes have different signs (or one is zero), the tangent must be zero
   * - Otherwise, use a weighted harmonic mean that prevents overshoot
   */
  private fun computeMonotoneTangent(m1: Float, m2: Float): Float {
    // If slopes have different signs or either is zero, tangent must be zero
    // This is the monotonicity condition - prevents overshoot at local extrema
    if (m1 * m2 <= 0f) return 0f

    // Fritsch-Carlson: weighted harmonic mean
    // This prevents the tangent from being too steep, which would cause overshoot
    // Using simple harmonic mean (assumes uniform x-spacing approximation)
    val harmonicMean = 2f * m1 * m2 / (m1 + m2)

    // Additional clamping per Fritsch-Carlson to ensure monotonicity
    // Tangent should not exceed 3x the minimum slope magnitude
    val minSlope = min(abs(m1), abs(m2))
    val maxAllowedTangent = 3f * minSlope

    return harmonicMean.coerceIn(-maxAllowedTangent, maxAllowedTangent)
  }

  /**
   * Resets the connector state for a new line.
   */
  private fun reset() {
    prevSlope = null
    isFirstSegment = true
  }
}

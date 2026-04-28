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

package com.patrykandpatrick.vico.core.cartesian

import android.graphics.RectF
import androidx.annotation.RestrictTo
import com.patrykandpatrick.vico.core.cartesian.Scroll.Absolute
import com.patrykandpatrick.vico.core.cartesian.layer.CartesianLayerDimensions

/** Represents a [CartesianChart] scroll value or delta. */
public sealed interface Scroll {
  /** Represents a [CartesianChart] scroll value. */
  public fun interface Absolute : Scroll {
    /** Returns the scroll value. */
    public fun getValue(
      context: CartesianMeasuringContext,
      layerDimensions: CartesianLayerDimensions,
      bounds: RectF,
      maxValue: Float,
    ): Float

    /** Houses [Scroll.Absolute] singletons and factory functions. */
    public companion object {
      /** Corresponds to zero. */
      public val Start: Absolute = Absolute { _, _, _, _ -> 0f }

      /** Corresponds to the maximum scroll value. */
      public val End: Absolute = Absolute { _, _, _, maxValue -> maxValue }

      /** Uses a scroll value of the specified number of pixels. */
      public fun pixels(pixels: Float): Absolute = Absolute { _, _, _, _ -> pixels }

      /**
       * Scrolls to the specified _x_ coordinate, positioning it anywhere between the start edge
       * ([bias] = 0) and the end edge ([bias] = 1) of the [CartesianChart].
       */
      public fun x(x: Double, bias: Float = 0f): Absolute =
        Absolute { context, layerDimensions, bounds, _ ->
          layerDimensions.startPadding +
            ((x - context.ranges.minX) / context.ranges.xStep).toFloat() *
              layerDimensions.xSpacing - bias * bounds.width()
        }

      /**
       * Scrolls to the specified _x_ coordinate with a padding offset, so [x] appears at
       * [paddingXStep] distance from the start edge of the chart, where [paddingXStep] is in
       * units of xStep (e.g. 0.5 = half a step from edge). Position is adjusted by [bias]
       * between start edge (0) and end edge (1).
       *
       * Padding is applied as a pixel-space offset (paddingXStep × xSpacing) and does not
       * depend on the chart's data range, so this is safe even when the data range is
       * degenerate (single-window state, empty data, or transient layout passes before the
       * model producer commits a real range).
       */
      public fun xWithPadding(x: Double, paddingXStep: Double, bias: Float = 0f): Absolute =
        Absolute { context, layerDimensions, bounds, _ ->
          layerDimensions.startPadding +
            ((x - context.ranges.minX) / context.ranges.xStep).toFloat() *
              layerDimensions.xSpacing -
            (paddingXStep * layerDimensions.xSpacing).toFloat() -
            bias * bounds.width()
        }
    }
  }

  /** Represents a [CartesianChart] scroll delta. */
  public fun interface Relative : Scroll {
    /** Returns the scroll delta. */
    public fun getDelta(
      context: CartesianMeasuringContext,
      layerDimensions: CartesianLayerDimensions,
      bounds: RectF,
      maxValue: Float,
    ): Float

    /** Houses [Scroll.Relative] factory functions. */
    public companion object {
      /** Scrolls by the specified number of pixels. */
      public fun pixels(pixels: Float): Relative = Relative { _, _, _, _ -> pixels }

      /** Scrolls by the specified number of _x_ units. */
      public fun x(x: Double): Relative = Relative { context, layerDimensions, _, _ ->
        (x / context.ranges.xStep).toFloat() * layerDimensions.xSpacing
      }
    }
  }
}

/** @suppress */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public fun Scroll.getDelta(
  context: CartesianMeasuringContext,
  layerDimensions: CartesianLayerDimensions,
  bounds: RectF,
  maxValue: Float,
  value: Float,
): Float =
  when (this) {
    is Absolute -> getValue(context, layerDimensions, bounds, maxValue) - value
    is Scroll.Relative -> getDelta(context, layerDimensions, bounds, maxValue)
  }

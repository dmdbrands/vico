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

import android.graphics.RectF
import android.util.Log
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import com.patrykandpatrick.vico.core.cartesian.AutoScrollCondition
import com.patrykandpatrick.vico.core.cartesian.CartesianChart
import com.patrykandpatrick.vico.core.cartesian.CartesianMeasuringContext
import com.patrykandpatrick.vico.core.cartesian.Scroll
import com.patrykandpatrick.vico.core.cartesian.VisibleRange
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModel
import com.patrykandpatrick.vico.core.cartesian.getDelta
import com.patrykandpatrick.vico.core.cartesian.getFullXRange
import com.patrykandpatrick.vico.core.cartesian.getMaxScrollDistance
import com.patrykandpatrick.vico.core.cartesian.getVisibleAxisLabels
import com.patrykandpatrick.vico.core.cartesian.getVisibleXRange
import com.patrykandpatrick.vico.core.cartesian.layer.CartesianLayerDimensions
import com.patrykandpatrick.vico.core.common.rangeWith
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Houses information on a [CartesianChart]’s scroll value. Allows for scroll customization and
 * programmatic scrolling.
 */
public class VicoScrollState {
  private val initialScroll: Scroll.Absolute
  private val autoScroll: Scroll
  private val autoScrollCondition: AutoScrollCondition
  private val autoScrollAnimationSpec: AnimationSpec<Float>
  private val _value: MutableFloatState
  private val _maxValue = mutableFloatStateOf(0f)
  private var initialScrollHandled: Boolean
  internal var context: CartesianMeasuringContext? = null
  internal var layerDimensions: CartesianLayerDimensions? = null
  internal var bounds: RectF? = null
  internal val scrollEnabled: Boolean
  internal val pointerXDeltas = MutableSharedFlow<Float>(extraBufferCapacity = 1)

  /** Function for custom snap behavior - takes current X label and returns snapped X label */
  public var snapToLabelFunction: ((Double?, Boolean , Boolean) -> Double)? = null

  private var boundaryDelayStartTime: Long = 0L
  private val boundaryDelayMs = 300L

  private var _visibleRange : MutableSharedFlow<VisibleRange?> = MutableStateFlow(null)
  /** StateFlow that emits the current visible range when scrolling starts or during scroll. */
  public val visibleRange: SharedFlow<VisibleRange?>
    get() = _visibleRange.asSharedFlow()

  /** The current visible range (for direct access). */
  public var currentVisibleRange: VisibleRange? = null
    private set

  /** The current measuring context (for snap calculations). */
  public val measuringContext: CartesianMeasuringContext?
    get() = this.context

  /** The current layer dimensions (for snap calculations). */
  public val currentLayerDimensions: CartesianLayerDimensions?
    get() = this.layerDimensions

  /** The current bounds (for snap calculations). */
  public val currentBounds: RectF?
    get() = this.bounds

  /** Whether scrolling is currently in progress. */
  internal val isScrollInProgress: Boolean
    get() {
      val baseScrollInProgress = scrollableState.isScrollInProgress
      val currentTime = System.currentTimeMillis()
      // If scroll is at boundaries (0f or maxValue) and not actively scrolling
      if (!baseScrollInProgress && (value == 0f || value == maxValue)) {
        Log.i("PointerXDeltas", "isScrollInProgress: $baseScrollInProgress , value: $value, maxValue: $maxValue")
        // Start the delay timer if not already started
        if (boundaryDelayStartTime == 0L ) {
          boundaryDelayStartTime = currentTime
          return true
        } else {
          if(currentTime - boundaryDelayStartTime < boundaryDelayMs) {
            return true
          }
        }
      } else {
        // Reset delay timer if not at boundaries or actively scrolling
        boundaryDelayStartTime = 0L
      }

      return baseScrollInProgress
    }

  internal val scrollableState = ScrollableState { delta ->
    val oldValue = value
    Log.i("VicoScroll", "delta: $delta, oldValue: $oldValue, value: $value")
    value += delta
    val consumedValue = value - oldValue
    if (oldValue + delta == value) {
      delta
    } else {
      pointerXDeltas.tryEmit(consumedValue - delta)
      consumedValue
    }
  }

  /** The current scroll value (in pixels). */
  public var value: Float
    get() = _value.floatValue
    private set(newValue) {
      val oldValue = value
      _value.floatValue = newValue.coerceIn(0f.rangeWith(maxValue))
      if (value != oldValue) {
        pointerXDeltas.tryEmit(oldValue - value)
      }
    }

  /** The maximum scroll value (in pixels). */
  public var maxValue: Float
    get() = _maxValue.floatValue
    internal set(newMaxValue) {
      if (newMaxValue == maxValue) return
      _maxValue.floatValue = newMaxValue
      value = value
    }

  internal constructor(
    scrollEnabled: Boolean,
    initialScroll: Scroll.Absolute,
    autoScroll: Scroll,
    autoScrollCondition: AutoScrollCondition,
    autoScrollAnimationSpec: AnimationSpec<Float>,
    value: Float,
    initialScrollHandled: Boolean,
  ) {
    this.scrollEnabled = scrollEnabled
    this.initialScroll = initialScroll
    this.autoScroll = autoScroll
    this.autoScrollCondition = autoScrollCondition
    this.autoScrollAnimationSpec = autoScrollAnimationSpec
    _value = mutableFloatStateOf(value)
    this.initialScrollHandled = initialScrollHandled
  }

  /**
   * Houses information on a [CartesianChart]’s scroll value. Allows for scroll customization and
   * programmatic scrolling.
   *
   * @param scrollEnabled whether scroll is enabled.
   * @param initialScroll represents the initial scroll value.
   * @param autoScroll represents the scroll value or delta for automatic scrolling.
   * @param autoScrollCondition defines when an automatic scroll should occur.
   * @param autoScrollAnimationSpec the [AnimationSpec] for automatic scrolling.
   */
  public constructor(
    scrollEnabled: Boolean,
    initialScroll: Scroll.Absolute,
    autoScroll: Scroll,
    autoScrollCondition: AutoScrollCondition,
    autoScrollAnimationSpec: AnimationSpec<Float>,
  ) : this(
    scrollEnabled = scrollEnabled,
    initialScroll = initialScroll,
    autoScroll = autoScroll,
    autoScrollCondition = autoScrollCondition,
    autoScrollAnimationSpec = autoScrollAnimationSpec,
    value = 0f,
    initialScrollHandled = false,
  )

  private inline fun withUpdated(
    block: (CartesianMeasuringContext, CartesianLayerDimensions, RectF) -> Unit
  ) {
    val context = this.context
    val layerDimensions = this.layerDimensions
    val bounds = this.bounds
    if (context != null && layerDimensions != null && bounds != null) {
      block(context, layerDimensions, bounds)
    }
  }

  private fun emitVisibleRange(context: CartesianMeasuringContext, layerDimensions: CartesianLayerDimensions, bounds: RectF) {
      val fullXRange = context.getFullXRange(layerDimensions)
      val visibleXRange = context.getVisibleXRange(bounds, layerDimensions, value)
      val visibleRange = VisibleRange(
        visibleXRange = visibleXRange,
        fullXRange = fullXRange,
        scrollValue = value,
        maxScrollValue = maxValue
      )
      _visibleRange.tryEmit(visibleRange)
      currentVisibleRange = visibleRange
  }

  internal fun update(
    context: CartesianMeasuringContext,
    bounds: RectF,
    layerDimensions: CartesianLayerDimensions,
  ) {
    this.context = context
    this.layerDimensions = layerDimensions
    this.bounds = bounds
    maxValue = context.getMaxScrollDistance(bounds.width(), layerDimensions)
    if (!initialScrollHandled) {
      value = initialScroll.getValue(context, layerDimensions, bounds, maxValue)
      initialScrollHandled = true
      // Don't emit visible range on initial setup
    } else {
        emitVisibleRange(context, layerDimensions, bounds)
    }
  }

  internal suspend fun autoScroll(model: CartesianChartModel, oldModel: CartesianChartModel?) {
    if (!autoScrollCondition.shouldScroll(oldModel, model)) return
    if (scrollableState.isScrollInProgress)
      scrollableState.stopScroll(MutatePriority.PreventUserInput)
    animateScroll(autoScroll, autoScrollAnimationSpec)
  }

  internal fun clearUpdated() {
    context = null
    layerDimensions = null
    bounds = null
  }

  /** Triggers a scroll. */
  public suspend fun scroll(scroll: Scroll) {
    withUpdated { context, layerDimensions, bounds ->
      scrollableState.scrollBy(scroll.getDelta(context, layerDimensions, bounds, maxValue, value))
    }
  }

  /** Triggers an animated scroll. */
  public suspend fun animateScroll(scroll: Scroll, animationSpec: AnimationSpec<Float> = spring()) {
    withUpdated { context, layerDimensions, bounds ->
      scrollableState.animateScrollBy(
        scroll.getDelta(context, layerDimensions, bounds, maxValue, value),
        animationSpec,
      )
    }
  }

  /**
   * Returns a list of x-axis label values that are currently visible in the chart.
   * This is a convenience method that uses the scroll state's current context.
   *
   * @param stepMultiplier optional multiplier for the step size (defaults to 1.0)
   * @return List of Double values representing the x-coordinates of visible axis labels, or empty list if context is not available
   */
  public fun getVisibleAxisLabels(stepMultiplier: Double = 1.0): List<Double> {
    val context = this.context
    val layerDimensions = this.layerDimensions
    val bounds = this.bounds

    return if (context != null && layerDimensions != null && bounds != null) {
      context.getVisibleAxisLabels(bounds, layerDimensions, value, stepMultiplier)
    } else {
      emptyList()
    }
  }

  internal companion object {
    fun Saver(
      scrollEnabled: Boolean,
      initialScroll: Scroll.Absolute,
      autoScroll: Scroll,
      autoScrollCondition: AutoScrollCondition,
      autoScrollAnimationSpec: AnimationSpec<Float>,
    ) =
      Saver<VicoScrollState, Pair<Float, Boolean>>(
        save = { it.value to it.initialScrollHandled },
        restore = { (value, initialScrollHandled) ->
          VicoScrollState(
            scrollEnabled,
            initialScroll,
            autoScroll,
            autoScrollCondition,
            autoScrollAnimationSpec,
            value,
            initialScrollHandled,
          )
        },
      )
  }
}

/** Creates and remembers a [VicoScrollState] instance. */
@Composable
public fun rememberVicoScrollState(
  scrollEnabled: Boolean = true,
  initialScroll: Scroll.Absolute = Scroll.Absolute.Start,
  autoScroll: Scroll = initialScroll,
  autoScrollCondition: AutoScrollCondition = AutoScrollCondition.Never,
  autoScrollAnimationSpec: AnimationSpec<Float> = spring(),
  snapToLabelFunction: ((Double?, Boolean , Boolean) -> Double)? = null,
): VicoScrollState =
  rememberSaveable(
    scrollEnabled,
    initialScroll,
    autoScroll,
    autoScrollCondition,
    autoScrollAnimationSpec,
    snapToLabelFunction,
    saver =
      remember(scrollEnabled, initialScroll, autoScrollCondition, autoScrollAnimationSpec) {
        VicoScrollState.Saver(
          scrollEnabled,
          initialScroll,
          autoScroll,
          autoScrollCondition,
          autoScrollAnimationSpec,
        )
      },
  ) {
    VicoScrollState(
      scrollEnabled,
      initialScroll,
      autoScroll,
      autoScrollCondition,
      autoScrollAnimationSpec,
    ).apply {
      this.snapToLabelFunction = snapToLabelFunction
    }
  }

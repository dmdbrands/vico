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

import android.util.Log
import android.view.ViewConfiguration
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.snapFlingBehavior
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.patrykandpatrick.vico.compose.common.detectZoomGestures
import com.patrykandpatrick.vico.core.common.Point
import com.patrykandpatrick.vico.core.cartesian.CartesianMeasuringContext
import com.patrykandpatrick.vico.core.cartesian.Scroll
import com.patrykandpatrick.vico.core.cartesian.getFullXRange
import com.patrykandpatrick.vico.core.cartesian.getVisibleAxisLabels
import com.patrykandpatrick.vico.core.cartesian.layer.CartesianLayerDimensions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.sign


private const val BASE_SCROLL_ZOOM_DELTA = 0.05f

// Interaction delay constants
private const val MARKER_DELAY_MS = 200L // 200ms delay for marker selection/scrubbing
private const val MOVEMENT_THRESHOLD = 10f // Minimum movement to consider as drag

private enum class InteractionMode {
    NONE,
    DECIDING, // Waiting to determine if it's scroll or marker interaction
    SCROLLING,
    MARKER_SELECTION,
    MARKER_SCRUBBING
}

private fun Offset.toPoint() = Point(x, y)

/**
 * Function type for snap behavior that takes current X label and returns snapped X label.
 *
 * @param currentXLabel The current X label value (center of visible range)
 * @param isDrag Whether this is a drag (low velocity) or fling (high velocity) operation
 * @param isForward Whether the scroll direction is forward (positive velocity)
 * @return The snapped X label value
 */
public typealias SnapToLabelFunction = (Double?, Boolean, Boolean) -> Double

/**
 * Creates a snap behavior that uses the snap function from VicoScrollState.
 * Based on Compose's SnapFlingBehavior implementation with two-phase animation.
 *
 * @param scrollState The VicoScrollState instance
 * @param config The snap behavior configuration (uses defaults if null)
 * @return FlingBehavior with label-based snapping
 */
private fun createSnapBehavior(scrollState: VicoScrollState, config: SnapBehaviorConfig? = null): FlingBehavior {
    val snapConfig = config ?: SnapBehaviorConfig()

    val snapLayoutInfoProvider = object : SnapLayoutInfoProvider {
        private var isDrag = false
        private var lastVelocity = 0f
        private var lastApproachTime = 0L

        override fun calculateApproachOffset(velocity: Float, decayOffset: Float): Float {
            val currentTime = System.currentTimeMillis()

            // Check if this is a new fling (significant velocity change or time gap)
            val isNewFling = kotlin.math.abs(velocity - lastVelocity) > snapConfig.velocityThresholds.velocityChangeThreshold ||
                           (currentTime - lastApproachTime) > snapConfig.velocityThresholds.timeGapThreshold

            if (isNewFling) {
                Log.i("SnapTargetFunction", "New fling detected - velocity: $velocity, lastVelocity: $lastVelocity")
                lastVelocity = velocity
                lastApproachTime = currentTime
                isDrag = false // Reset drag state for new fling
            }

            Log.i(
                "SnapTargetFunction",
                "calculateApproachOffset called with velocity: $velocity, decayOffset: $decayOffset, isNewFling: $isNewFling"
            )

            // Phase 1: Calculate how far to approach before snapping
            val bounds = scrollState.bounds?.width()
            val windowWidthPx = bounds ?: 0f

            if (velocity.absoluteValue < snapConfig.velocityThresholds.lowFling) {
                isDrag = true
                return 0f
            }

            // Determine how many windows to move based on velocity using config values
            val windowsToMove = when {
                velocity.absoluteValue >= snapConfig.velocityThresholds.highFling -> snapConfig.windowMovement.highFlingWindows
                velocity.absoluteValue >= snapConfig.velocityThresholds.mediumFling -> snapConfig.windowMovement.mediumFlingWindows
                velocity.absoluteValue >= snapConfig.velocityThresholds.lowFling -> snapConfig.windowMovement.lowFlingWindows
                else -> snapConfig.windowMovement.dragWindows
            }

            // Calculate the approach distance based on window width in pixels and velocity direction
            val approachDistance = windowWidthPx * windowsToMove * velocity.sign

            Log.i("SnapBehavior", "Approach Phase - windowWidthPx: $windowWidthPx, windowsToMove: $windowsToMove, velocity: $velocity")
            Log.i("SnapBehavior", "Approach Phase - approachDistance: $approachDistance")

            return approachDistance.toFloat()
        }

        override fun calculateSnapOffset(velocity: Float): Float {
            // Phase 2: Calculate the final snap offset using Scroll.Absolute.x()
            val initialRange = scrollState.currentVisibleRange?.visibleXRange?.start
            val approachedLabel = snapConfig.snapToLabelFunction?.let { it(initialRange, isDrag, velocity.sign > 0f) }
            Log.i("SnapBehavior", "isDrag: $isDrag, velocity: $velocity")

            val layerDimensions = scrollState.currentLayerDimensions
            val context = scrollState.context
            val bounds = scrollState.bounds
            val maxValue = scrollState.maxValue

            val finalSnapOffset =
                if (approachedLabel != null && layerDimensions != null && context != null && bounds != null) {
                    // Use Scroll.Absolute.x() to calculate the correct scroll position
                    val targetScrollPosition = Scroll.Absolute.x(approachedLabel, bias = 0f)
                        .getValue(context, layerDimensions, bounds, maxValue)

                    // Get current scroll position
                    val currentScrollPosition = scrollState.value

                    // Return the difference (offset to reach target)
                    val offset = targetScrollPosition - currentScrollPosition

                    Log.i("SnapTargetFunction", "Snap Phase - approachedLabel: $approachedLabel, targetScrollPosition: $targetScrollPosition, currentScrollPosition: $currentScrollPosition, offset: $offset")

                    offset
                } else {
                    0f
                }

            return finalSnapOffset
        }
    }

    return snapFlingBehavior(
        snapLayoutInfoProvider = snapLayoutInfoProvider,
        decayAnimationSpec = exponentialDecay(
            frictionMultiplier = snapConfig.animation.decayFrictionMultiplier
        ),
        snapAnimationSpec = tween(
            durationMillis = snapConfig.animation.snapDurationMillis,
            easing = snapConfig.animation.snapEasing
        )
    )
}

/**
 * Internal modifier that handles pointer input for chart interactions.
 * Provides access to scroll state through the scrollState parameter.
 * Use scrollState.isScrollInProgress to check if scrolling is active.
 */
internal fun Modifier.pointerInput(
  scrollState: VicoScrollState,
  onPointerPositionChange: ((Point?) -> Unit)?,
  onZoom: ((Float, Offset) -> Unit)?,
  consumeMoveEvents: Boolean,
  isPointerSelectionInProgress: Boolean,
  scope: CoroutineScope,
  onSelectionStateChange: (Boolean) -> Unit,
) =
  scrollable(
      state = scrollState.scrollableState,
      orientation = Orientation.Horizontal,
      flingBehavior = createSnapBehavior(scrollState, scrollState.snapBehaviorConfig),
      enabled = scrollState.scrollEnabled && !isPointerSelectionInProgress,
      reverseDirection = true,
    )
    .pointerInput(onZoom, onPointerPositionChange) {
    var interactionMode by mutableStateOf(InteractionMode.NONE)
    var pressPosition: Point? = null
    var initialPressPosition: Offset? = null
    var delayJob: Job? = null
      var hasMoved = false


    fun startDelayTimer() {
      delayJob?.cancel()
      delayJob = scope.launch {
        delay(MARKER_DELAY_MS)
        if (interactionMode == InteractionMode.DECIDING) {
          interactionMode = InteractionMode.MARKER_SELECTION
          pressPosition?.let { position ->
            scrollState.emitInteractionEvent(ChartInteractionEvent.MarkerSelectionStarted(position))
          }
          onPointerPositionChange?.invoke(pressPosition)
          onSelectionStateChange(true)
          Log.i("PointerEvent", "Entered MARKER_SELECTION mode")
        }
      }
    }

    fun cancelDelayTimer() {
      delayJob?.cancel()
      delayJob = null
    }

    fun enterScrollMode() {
      if (interactionMode == InteractionMode.DECIDING) {
        interactionMode = InteractionMode.SCROLLING
        pressPosition?.let { position ->
          scrollState.emitInteractionEvent(ChartInteractionEvent.DragStarted(position))
        }
        onSelectionStateChange(false)
        cancelDelayTimer()
        Log.i("PointerEvent", "Entered SCROLLING mode")
      }
    }


    fun enterMarkerScrubbingMode() {
      if (interactionMode == InteractionMode.MARKER_SELECTION) {
        interactionMode = InteractionMode.MARKER_SCRUBBING
        Log.i("PointerEvent", "Entered MARKER_SCRUBBING mode")
      }
    }

    fun resetToNone() {
      interactionMode = InteractionMode.NONE
      onSelectionStateChange(false)
      cancelDelayTimer()
      pressPosition = null
      initialPressPosition = null
      scrollState.resetInteractionEvents()
    }

    awaitPointerEventScope {
      while (true) {
        val event = awaitPointerEvent()
        Log.i("PointerEvent", "event.type: ${event.type}, mode: $interactionMode")
        when {
          event.type == PointerEventType.Scroll && scrollState.scrollEnabled && onZoom != null -> {
            val zoomFactor = 1 - event.changes.first().scrollDelta.y * BASE_SCROLL_ZOOM_DELTA
            val centroid = event.changes.first().position
            scrollState.emitInteractionEvent(ChartInteractionEvent.Zoom(zoomFactor, centroid))
            onZoom(zoomFactor, centroid)
          }
          onPointerPositionChange == null -> continue
          event.type == PointerEventType.Press -> {
            if(scrollState.isScrollInProgress){
              continue
            }
            hasMoved = false
            val position = event.changes.first().position
            pressPosition = position.toPoint()
            initialPressPosition = position
            interactionMode = InteractionMode.DECIDING
            startDelayTimer()
            Log.i("PointerEvent", "Press - started DECIDING mode")
          }

          event.type == PointerEventType.Move -> {
            hasMoved = true
            val changes = event.changes.first()
            val currentPosition = changes.position
            val movement = initialPressPosition?.let {
              kotlin.math.abs((currentPosition - it).getDistance())
            } ?: 0f

            when (interactionMode) {
              InteractionMode.DECIDING -> {
                if (movement > MOVEMENT_THRESHOLD) {
                  // Immediate drag without delay - enter scroll mode
                  enterScrollMode()
                }
                // If movement is small, let the delay timer decide
              }
              InteractionMode.MARKER_SELECTION -> {
                if (movement > MOVEMENT_THRESHOLD) {
                  // Started dragging after delay - enter scrubbing mode
                  enterMarkerScrubbingMode()
                }
              }
              InteractionMode.MARKER_SCRUBBING -> {
                // Continue scrubbing - pass position to callback
                // Always consume move events when scrubbing to prevent external scroll interference
                changes.consume()
                val point = currentPosition.toPoint()
                scrollState.emitInteractionEvent(ChartInteractionEvent.MarkerScrubbing(point))
                onPointerPositionChange(point)
                Log.i("PointerEvent", "MARKER_SCRUBBING - position: $point")
              }
              InteractionMode.SCROLLING -> {
                // Let scroll system handle it - don't consume to allow free flow scrolling
                val point = currentPosition.toPoint()
                scrollState.emitInteractionEvent(ChartInteractionEvent.Dragging(point))
                onPointerPositionChange(null)
                Log.i("PointerEvent", "SCROLLING - letting scroll handle")
              }
              else -> {
                // No action
              }
            }
          }
          event.type == PointerEventType.Release -> {
            if(interactionMode == InteractionMode.DECIDING && !hasMoved){
              interactionMode = InteractionMode.MARKER_SELECTION
            }
            when (interactionMode) {
              InteractionMode.MARKER_SELECTION -> {
                // Touch and hold, then release - marker selection
                scrollState.emitInteractionEvent(ChartInteractionEvent.MarkerInteractionEnded(pressPosition))
                if(!scrollState.isScrollInProgress) {
                  onPointerPositionChange(pressPosition)
                  Log.i("PointerEvent", "MARKER_SELECTION - selected and cleared")
                } else {
                  onPointerPositionChange(null)
                  Log.i("PointerEvent", "MARKER_SELECTION - scroll in progress")
                }
              }
              InteractionMode.MARKER_SCRUBBING -> {
                // Touch, hold, drag, then release - clear scrubbing
                scrollState.emitInteractionEvent(ChartInteractionEvent.MarkerInteractionEnded(pressPosition))
                // Consume release event to prevent external scroll interference
                event.changes.first().consume()
                Log.i("PointerEvent", "MARKER_SCRUBBING - cleared")
              }
              InteractionMode.SCROLLING -> {
                // Touch and drag without hold - scroll ended
                scrollState.emitInteractionEvent(ChartInteractionEvent.DragEnded(pressPosition))
                onPointerPositionChange(null)
                Log.i("PointerEvent", "SCROLLING - ended")
              }
              InteractionMode.DECIDING -> {
                // Quick tap - marker selection
                scrollState.emitInteractionEvent(ChartInteractionEvent.MarkerInteractionEnded(pressPosition))
                onPointerPositionChange(null)
                Log.i("PointerEvent", "DECIDING - quick tap selection")
              }
              else -> {
                // No action
              }
            }
            resetToNone()
          }

        }
      }
    }
  }
    .then(
      if (scrollState.scrollEnabled && onZoom != null) {
        Modifier.pointerInput(onPointerPositionChange, onZoom) {
          detectZoomGestures { centroid, zoom ->
            scrollState.emitInteractionEvent(ChartInteractionEvent.Zoom(zoom, centroid))
            onPointerPositionChange?.invoke(null)
            onZoom(zoom, centroid)
          }
        }
      } else {
        Modifier
      }
    )

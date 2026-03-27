/*
 * Copyright 2026 by Patryk Goworowski and Patrick Michalik.
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

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.data.*
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.MutableCartesianLayerDimensions
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerController.Lock
import com.patrykandpatrick.vico.compose.cartesian.marker.Interaction
import com.patrykandpatrick.vico.compose.common.*
import com.patrykandpatrick.vico.compose.common.Defaults.CHART_HEIGHT
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import kotlin.math.abs
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Displays a [CartesianChart].
 *
 * @param chart the [CartesianChart].
 * @param modelProducer creates and updates the [CartesianChartModel].
 * @param modifier the modifier to be applied to the chart.
 * @param scrollState houses information on the [CartesianChart]’s scroll value. Allows for scroll
 *   customization and programmatic scrolling.
 * @param zoomState houses information on the [CartesianChart]’s zoom factor. Allows for zoom
 *   customization.
 * @param animationSpec the [AnimationSpec] for difference animations.
 * @param animateIn whether to run an initial animation when the [CartesianChartHost] enters
 *   composition. The animation is skipped for previews.
 * @param placeholder shown when no [CartesianChartModel] is available.
 */
@Composable
public fun CartesianChartHost(
  chart: CartesianChart,
  modelProducer: CartesianChartModelProducer,
  modifier: Modifier = Modifier,
  scrollState: VicoScrollState = rememberVicoScrollState(),
  zoomState: VicoZoomState = rememberDefaultVicoZoomState(scrollState.scrollEnabled),
  animationSpec: AnimationSpec<Float>? = defaultCartesianDiffAnimationSpec,
  animateIn: Boolean = true,
  placeholder: @Composable BoxScope.() -> Unit = {},
) {
  val mutableRanges = remember { MutableCartesianChartRanges() }
  val modelWrapper by modelProducer.collectAsState(chart, animationSpec, animateIn, mutableRanges)
  val (model, previousModel, initialRanges, extraStore) = modelWrapper

  // Scroll-aware range: wrap initialRanges with animated Y values.
  var animatedYRange by remember { mutableStateOf(Double.NaN to Double.NaN) }
  val hasValidAnimatedRange = !animatedYRange.first.isNaN() && !animatedYRange.second.isNaN()
  val isInitialRangesReady = initialRanges !== CartesianChartRanges.Empty

  val hasScrollAwareProvider = remember(chart) {
    chart.layers.any { it is LineCartesianLayer && it.internalRangeProvider is ScrollAwareRangeProvider }
  }

  val ranges = if (hasValidAnimatedRange && isInitialRangesReady) {
    AnimatedYCartesianChartRanges(initialRanges, animatedYRange.first, animatedYRange.second)
  } else {
    initialRanges
  }

  if (model != null) {
    ScrollAwareRangeEffect(chart, model) { minY, maxY ->
      animatedYRange = minY to maxY
    }
  }

  // When using ScrollAwareRangeProvider, hide chart until visible-window range is ready.
  // Canvas still runs (to emit scroll info) but is invisible — zero flash of wrong range.
  val chartAlpha = if (hasScrollAwareProvider && !hasValidAnimatedRange) 0f else 1f

  CartesianChartHostBox(modifier.alpha(chartAlpha)) {
    if (model != null) {
      CartesianChartHostImpl(
        chart,
        model,
        scrollState,
        zoomState,
        ranges,
        previousModel,
        extraStore,
      )
    } else {
      placeholder()
    }
  }
}

/**
 * Displays a [CartesianChart]. This function accepts a [CartesianChartModel]. For dynamic data, use
 * the function overload that accepts a [CartesianChartModelProducer] instance.
 *
 * @param chart the [CartesianChart].
 * @param model the [CartesianChartModel].
 * @param modifier the modifier to be applied to the chart.
 * @param scrollState houses information on the [CartesianChart]’s scroll value. Allows for scroll
 *   customization and programmatic scrolling.
 * @param zoomState houses information on the [CartesianChart]’s zoom factor. Allows for zoom
 *   customization.
 */
@Composable
public fun CartesianChartHost(
  chart: CartesianChart,
  model: CartesianChartModel,
  modifier: Modifier = Modifier,
  scrollState: VicoScrollState = rememberVicoScrollState(),
  zoomState: VicoZoomState = rememberDefaultVicoZoomState(scrollState.scrollEnabled),
) {
  val ranges = remember { MutableCartesianChartRanges() }
  remember(chart, model) {
    ranges.reset()
    chart.updateRanges(ranges, model)
  }
  CartesianChartHostBox(modifier) {
    CartesianChartHostImpl(chart, model, scrollState, zoomState, ranges.toImmutable())
  }
}

@Composable
internal fun CartesianChartHostImpl(
  chart: CartesianChart,
  model: CartesianChartModel,
  scrollState: VicoScrollState,
  zoomState: VicoZoomState,
  ranges: CartesianChartRanges,
  previousModel: CartesianChartModel? = null,
  extraStore: ExtraStore = ExtraStore.Empty,
) {
  var markerX by rememberSaveable { mutableStateOf<Double?>(null) }
  var markerSeriesIndex by rememberSaveable { mutableStateOf<Int?>(null) }
  var lastAcceptedInteraction by
    rememberSaveable(saver = Interaction.Saver) { mutableStateOf(null) }
  val measuringContext =
    rememberCartesianMeasuringContext(
      extraStore = extraStore,
      model = model,
      ranges = ranges,
      scrollEnabled = scrollState.scrollEnabled,
      zoomEnabled = scrollState.scrollEnabled && zoomState.zoomEnabled,
      layerPadding =
        remember(chart.layerPadding, model.extraStore) { chart.layerPadding(model.extraStore) },
      markerX = markerX,
      markerSeriesIndex = markerSeriesIndex,
    )

  val coroutineScope = rememberCoroutineScope()
  var lastHandledModel by remember { ValueWrapper(model) }
  val layerDimensions = remember { MutableCartesianLayerDimensions() }

  // Cached ScrollAwareRangeProvider references + dedup state for Canvas emit
  val scrollAwareProviders = remember(chart) {
    chart.layers.mapNotNull { layer ->
      (layer as? LineCartesianLayer)?.internalRangeProvider as? ScrollAwareRangeProvider
    }
  }
  var lastEmittedScroll by remember { ValueWrapper(Float.NaN) }
  var lastEmittedXSpacing by remember { ValueWrapper(Float.NaN) }
  var lastEmittedChartWidth by remember { ValueWrapper(Float.NaN) }

  val onInteraction =
    remember(chart, layerDimensions, scrollState, ranges) {
      if (chart.marker != null) {
        { interaction: Interaction ->
          val x =
            measuringContext.value.pointerPositionToX(
              interaction.point,
              layerDimensions,
              chart.layerBounds,
              scrollState.value,
              ranges,
            )
          val targets =
            chart.getMarkerTargets(
              x,
              measuringContext.value.getVisibleXRange(
                layerDimensions,
                chart.layerBounds,
                scrollState.value,
              ),
            )
          val narrowedTargets: List<CartesianMarker.Target>
          val seriesIndex: Int?
          if (targets.isNotEmpty()) {
            val closestIndex =
              targets.indices.minBy { abs(targets[it].canvasX - interaction.point.x) }
            if (targets.distinctBy { it.canvasX }.size > 1) {
              narrowedTargets = listOf(targets[closestIndex])
              seriesIndex = closestIndex
            } else {
              narrowedTargets = targets
              seriesIndex = null
            }
          } else {
            narrowedTargets = targets
            seriesIndex = null
          }
          if (chart.markerController.shouldAcceptInteraction(interaction, narrowedTargets)) {
            val shouldShow = chart.markerController.shouldShowMarker(interaction, narrowedTargets)
            lastAcceptedInteraction = interaction
            if (shouldShow && narrowedTargets.isNotEmpty()) {
              markerX = narrowedTargets.first().x
              markerSeriesIndex = seriesIndex
            } else {
              markerX = null
              markerSeriesIndex = null
            }
          }
        }
      } else {
        null
      }
    }

  fun onViewportChange() {
    lastAcceptedInteraction
      ?.takeIf { chart.markerController.lock == Lock.Position }
      ?.let { onInteraction?.invoke(it) }
  }

  LaunchedEffect(model) { onViewportChange() }

  LaunchedEffect(scrollState.consumedXDeltas, scrollState.unconsumedXDeltas) {
    merge(scrollState.consumedXDeltas, scrollState.unconsumedXDeltas).collect { onViewportChange() }
  }

  LaunchedEffect(zoomState, scrollState) {
    zoomState.pendingScroll.collect { (scroll, maxValue) ->
      scrollState.scroll(scroll, maxValue)
      onViewportChange()
    }
  }

  DisposableEffect(scrollState) { onDispose { scrollState.clearUpdated() } }

  Canvas(
    modifier =
      Modifier.fillMaxSize()
        .pointerInput(
          scrollState = scrollState,
          consumeMoveEvents = chart.markerController.consumeMoveEvents,
          onInteraction = onInteraction,
          onZoom =
            remember(zoomState, scrollState, coroutineScope) {
              if (zoomState.zoomEnabled) {
                { factor, centroid ->
                  coroutineScope.launch { zoomState.zoom(factor, centroid.x) { scrollState.value } }
                }
              } else {
                null
              }
            },
          longPressEnabled = chart.markerController.acceptsLongPress,
        )
  ) {
    if (size.isEmpty()) return@Canvas
    measuringContext.value.canvasSize = size

    layerDimensions.clear()
    chart.prepare(measuringContext.value, layerDimensions)

    if (chart.layerBounds.isEmpty) return@Canvas

    zoomState.update(measuringContext.value, layerDimensions, chart.layerBounds, scrollState.value)
    scrollState.update(measuringContext.value, chart.layerBounds, layerDimensions)

    // Emit scroll info to ScrollAwareRangeProviders (cached list, deduplicated)
    if (scrollAwareProviders.isNotEmpty() && layerDimensions.xSpacing > 0f && !chart.layerBounds.isEmpty) {
      val sp = scrollState.value
      val xs = layerDimensions.xSpacing
      val cw = chart.layerBounds.width
      if (sp != lastEmittedScroll || xs != lastEmittedXSpacing || cw != lastEmittedChartWidth) {
        lastEmittedScroll = sp
        lastEmittedXSpacing = xs
        lastEmittedChartWidth = cw
        val scrollInfo = ScrollAwareRangeProvider.ScrollInfo(sp, xs, cw)
        scrollAwareProviders.forEach { it.scrollUpdates.tryEmit(scrollInfo) }
      }
    }

    if (model != lastHandledModel) {
      coroutineScope.launch { scrollState.autoScroll(model, previousModel) }
      lastHandledModel = model
    }

    val drawingContext =
      CartesianDrawingContext(
        measuringContext.value,
        drawContext.canvas,
        layerDimensions,
        chart.layerBounds,
        scrollState.value,
        zoomState.value,
        MutableDrawScope(this),
      )

    chart.draw(drawingContext)
    measuringContext.value.cacheStore.purge()
  }
}

@Composable
private fun CartesianChartHostBox(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
  Box(modifier = modifier.heightIn(max = CHART_HEIGHT.dp).fillMaxWidth(), content = content)
}

/**
 * Manages [ScrollAwareRangeProvider] lifecycle following iOS-like animation pattern:
 *
 * **iOS approach**: Labels cross-fade (opacity), chart content animates positionally, ~250ms.
 * **Our approximation**: Labels swap instantly, chart content animates via range interpolation.
 *
 * Fixes applied:
 * - Animatable initialized AFTER cache build (prevents flash from 0→actual on first load)
 * - Layer index resolved via chart.layers.indexOf (correct multi-layer mapping)
 * - NaN sentinel for uninitialized state (prevents premature animation)
 * - key(provider) ensures correct Compose state tracking per provider identity
 */
@OptIn(FlowPreview::class)
@Composable
private fun ScrollAwareRangeEffect(
  chart: CartesianChart,
  model: CartesianChartModel,
  onAnimatedRange: (minY: Double, maxY: Double) -> Unit,
) {
  val providers = remember(chart) {
    chart.layers.mapNotNull { layer ->
      if (layer is LineCartesianLayer) {
        (layer.internalRangeProvider as? ScrollAwareRangeProvider)?.let { it to layer }
      } else {
        null
      }
    }
  }
  if (providers.isEmpty()) return

  providers.forEach { (provider, layer) ->
    // key(provider) ensures Compose tracks state per provider identity,
    // even if the providers list order changes between recompositions.
    key(provider) {
    val layerIndex = remember(chart, layer) { chart.layers.indexOf(layer) }

    // Animatable keyed on model — reinitializes with NaN on model/config change.
    // Correct values are set in LaunchedEffect(model) via snapTo().
    val animMinY = remember(model) { Animatable(Float.NaN) }
    val animMaxY = remember(model) { Animatable(Float.NaN) }
    // Track whether the first visible-range update has happened.
    // First update uses snapTo (no animation) because the initial range
    // is computed from the FULL dataset, not the visible window.
    var isFirstScrollUpdate by remember { mutableStateOf(true) }

    // Build cache only — don't compute range from full dataset.
    // The correct range comes from the visible window (first scroll event).
    LaunchedEffect(model) {
      val layerModel = model.models.getOrNull(layerIndex) as? LineCartesianLayerModel
        ?: return@LaunchedEffect
      provider.buildCache(layerModel.series)
      isFirstScrollUpdate = true
    }

    // First scroll event: process IMMEDIATELY (no debounce) and SNAP (no animation).
    // This sets the correct visible-window range on the very first frame that
    // Canvas reports scroll info — no flash of full-dataset range.
    LaunchedEffect(provider, model) {
      if (!isFirstScrollUpdate) return@LaunchedEffect
      // Wait for the first scroll event (no debounce)
      val firstScrollInfo = provider.scrollUpdates.first { provider.isCacheReady }
      val visible = provider.computeVisibleRange(firstScrollInfo)
      val result = visible?.let { provider.computeDisplayRange(it.first, it.second) }
      if (result != null) {
        val (range, ticks) = result
        isFirstScrollUpdate = false
        provider.currentMinY = range.start
        provider.currentMaxY = range.endInclusive
        provider.currentTicks = ticks
        animMinY.snapTo(range.start.toFloat())
        animMaxY.snapTo(range.endInclusive.toFloat())
        onAnimatedRange(range.start, range.endInclusive)
      }
    }

    // Subsequent scroll events: debounced + animated (iOS-like).
    LaunchedEffect(provider) {
      provider.scrollUpdates
        .debounce(provider.debounceMs)
        .collect { scrollInfo ->
          if (!provider.isCacheReady || isFirstScrollUpdate) return@collect
          if (animMinY.value.isNaN()) return@collect

          val visible = provider.computeVisibleRange(scrollInfo) ?: return@collect
          val result = provider.computeDisplayRange(visible.first, visible.second) ?: return@collect
          val (range, newTicks) = result
          val targetMinY = range.start.toFloat()
          val targetMaxY = range.endInclusive.toFloat()

          if (abs(targetMinY - animMinY.value) < 0.01f &&
            abs(targetMaxY - animMaxY.value) < 0.01f) return@collect

          // iOS cross-fade approximation: swap ticks instantly
          provider.currentTicks = newTicks

          // Animate range — chart content scales smoothly
          val minJob = launch {
            animMinY.animateTo(targetMinY, tween(provider.animDurationMs))
          }
          val maxJob = launch {
            animMaxY.animateTo(targetMaxY, tween(provider.animDurationMs))
          }
          minJob.join()
          maxJob.join()
        }
    }
    

    // Push animated values to Compose State on each animation frame
    LaunchedEffect(Unit) {
      snapshotFlow { animMinY.value to animMaxY.value }.collect { (minY, maxY) ->
        // Skip NaN frames (before initialization)
        if (minY.isNaN() || maxY.isNaN()) return@collect
        provider.currentMinY = minY.toDouble()
        provider.currentMaxY = maxY.toDouble()
        onAnimatedRange(minY.toDouble(), maxY.toDouble())
      }
    }
    } // end key(provider)
  }
}

/**
 * A lightweight [CartesianChartRanges] wrapper that overrides Y range with animated values
 * but delegates everything else to the original ranges. No reset/rebuild needed per frame.
 */
private class AnimatedYCartesianChartRanges(
  private val delegate: CartesianChartRanges,
  animMinY: Double,
  animMaxY: Double,
) : CartesianChartRanges {
  override val minX: Double get() = delegate.minX
  override val maxX: Double get() = delegate.maxX
  override val xStep: Double get() = delegate.xStep

  // Single YRange instance — reused for all getYRange() calls in this frame.
  private val yRange = object : CartesianChartRanges.YRange {
    override val minY: Double = animMinY
    override val maxY: Double = animMaxY
    override val length: Double = (animMaxY - animMinY).coerceAtLeast(1e-6)
  }

  override fun getYRange(axisPosition: Axis.Position.Vertical?): CartesianChartRanges.YRange = yRange
}

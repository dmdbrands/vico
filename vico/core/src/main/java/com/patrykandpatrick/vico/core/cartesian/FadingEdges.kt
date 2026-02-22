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

import android.animation.TimeInterpolator
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.compose.runtime.Immutable
import com.patrykandpatrick.vico.core.common.Defaults.FADING_EDGE_VISIBILITY_THRESHOLD_DP
import com.patrykandpatrick.vico.core.common.Defaults.FADING_EDGE_WIDTH_DP
import com.patrykandpatrick.vico.core.common.copyColor

private const val FULL_ALPHA = 0xFF
private const val FULL_FADE: Int = 0xFF000000.toInt()
private const val NO_FADE: Int = 0x00000000

/**
 * [FadingEdges] applies a horizontal fade to the edges of the chart area for scrollable charts.
 * This effect indicates that there’s more content beyond a given edge, and the user can scroll to
 * reveal it.
 *
 * @param startWidthDp the width of the fade overlay for the start edge (in dp).
 * @param endWidthDp the width of the fade overlay for the end edge (in dp).
 * @param visibilityThresholdDp the scroll distance over which the overlays fade in and out (in dp).
 * @param visibilityInterpolator used for the fading edges’ fade-in and fade-out animations. This is
 *   a mapping of the degree to which [visibilityThresholdDp] has been satisfied to the opacity of
 *   the fading edges.
 * @param startPaddingXStep optional padding for the start fade width as a fraction of x-step (e.g.
 *   0.2 = 20% of one step). When non-null, converted to pixels at draw time via
 *   [CartesianLayerDimensions.xSpacing]; the effective start fade width is the max of
 *   [startWidthDp] and this value. When null, [CartesianLayerDimensions.startPadding] is used.
 * @param endPaddingXStep optional padding for the end fade width as a fraction of x-step. When
 *   non-null, converted to pixels at draw time; effective end fade width is the max of [endWidthDp]
 *   and this value. When null, [CartesianLayerDimensions.endPadding] is used.
 */
@Immutable
public open class FadingEdges(
  protected val startWidthDp: Float = FADING_EDGE_WIDTH_DP,
  protected val endWidthDp: Float = FADING_EDGE_WIDTH_DP,
  protected val visibilityThresholdDp: Float = FADING_EDGE_VISIBILITY_THRESHOLD_DP,
  protected val visibilityInterpolator: TimeInterpolator = AccelerateDecelerateInterpolator(),
  protected val startPaddingXStep: Double? = null,
  protected val endPaddingXStep: Double? = null,
) {
  private val paint: Paint = Paint()

  private val rect: RectF = RectF()

  /**
   * Creates a [FadingEdges] instance with fading edges of equal width.
   *
   * @param widthDp the width of the fade overlays (in dp).
   * @param visibilityThresholdDp the scroll distance over which the overlays fade in and out (in
   *   dp).
   * @param visibilityInterpolator used for the fading edges’ fade-in and fade-out animations. This
   *   is a mapping of the degree to which [visibilityThresholdDp] has been satisfied to the opacity
   *   of the fading edges.
   */
  public constructor(
    widthDp: Float = FADING_EDGE_WIDTH_DP,
    visibilityThresholdDp: Float = FADING_EDGE_VISIBILITY_THRESHOLD_DP,
    visibilityInterpolator: TimeInterpolator = AccelerateDecelerateInterpolator(),
  ) : this(
    startWidthDp = widthDp,
    endWidthDp = widthDp,
    visibilityThresholdDp = visibilityThresholdDp,
    visibilityInterpolator = visibilityInterpolator,
  )

  init {
    require(value = startWidthDp >= 0) { "`startWidthDp` must be nonnegative." }
    require(value = endWidthDp >= 0) { "`endWidthDp` must be nonnegative." }

    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
  }

  internal fun draw(context: CartesianDrawingContext) {
    with(context) {
      val layerDimensions = context.layerDimensions
      val startPaddingPixels =
        startPaddingXStep?.let { (it * layerDimensions.xSpacing).toFloat() }
          ?: layerDimensions.startPadding
      val startFadeWidth = maxOf(startWidthDp.pixels, startPaddingPixels)
      val endPaddingPixels =
        endPaddingXStep?.let { (it * layerDimensions.xSpacing).toFloat() }
          ?: layerDimensions.endPadding
      val endFadeWidth = maxOf(endWidthDp.pixels, endPaddingPixels)

      val maxScroll = getMaxScrollDistance()
      var fadeAlphaFraction: Float

      if (scrollEnabled && startFadeWidth > 0f && scroll > 0f) {
        fadeAlphaFraction = (scroll / visibilityThresholdDp.pixels).coerceAtMost(1f)

        drawFadingEdge(
          left = layerBounds.left,
          top = layerBounds.top,
          right = layerBounds.left + startFadeWidth,
          bottom = layerBounds.bottom,
          direction = -1,
          alpha = (visibilityInterpolator.getInterpolation(fadeAlphaFraction) * FULL_ALPHA).toInt(),
        )
      }

      if (scrollEnabled && endFadeWidth > 0f && scroll < maxScroll) {
        fadeAlphaFraction = ((maxScroll - scroll) / visibilityThresholdDp.pixels).coerceAtMost(1f)

        drawFadingEdge(
          left = layerBounds.right - endFadeWidth,
          top = layerBounds.top,
          right = layerBounds.right,
          bottom = layerBounds.bottom,
          direction = 1,
          alpha = (visibilityInterpolator.getInterpolation(fadeAlphaFraction) * FULL_ALPHA).toInt(),
        )
      }
    }
  }

  private fun CartesianDrawingContext.drawFadingEdge(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    direction: Int,
    alpha: Int,
  ) {
    rect.set(left, top, right, bottom)

    val faded = FULL_FADE.copyColor(alpha = alpha)

    paint.shader =
      LinearGradient(
        rect.left,
        0f,
        rect.right,
        0f,
        if (direction < 0) faded else NO_FADE,
        if (direction < 0) NO_FADE else faded,
        Shader.TileMode.CLAMP,
      )
    canvas.drawRect(rect, paint)
  }

  override fun equals(other: Any?): Boolean =
    this === other ||
      other is FadingEdges &&
      startWidthDp == other.startWidthDp &&
      endWidthDp == other.endWidthDp &&
      visibilityThresholdDp == other.visibilityThresholdDp &&
      visibilityInterpolator == other.visibilityInterpolator &&
      startPaddingXStep == other.startPaddingXStep &&
      endPaddingXStep == other.endPaddingXStep

  override fun hashCode(): Int {
    var result = startWidthDp.hashCode()
    result = 31 * result + endWidthDp.hashCode()
    result = 31 * result + visibilityThresholdDp.hashCode()
    result = 31 * result + visibilityInterpolator.hashCode()
    result = 31 * result + (startPaddingXStep?.hashCode() ?: 0)
    result = 31 * result + (endPaddingXStep?.hashCode() ?: 0)
    return result
  }
}

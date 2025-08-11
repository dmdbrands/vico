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

package com.patrykandpatrick.vico.core.cartesian.decoration

import androidx.annotation.RestrictTo
import com.patrykandpatrick.vico.core.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.core.cartesian.axis.Axis
import com.patrykandpatrick.vico.core.common.Position
import com.patrykandpatrick.vico.core.common.component.LineComponent
import com.patrykandpatrick.vico.core.common.component.TextComponent
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import com.patrykandpatrick.vico.core.common.getEnd
import com.patrykandpatrick.vico.core.common.getStart
import com.patrykandpatrick.vico.core.common.half
import com.patrykandpatrick.vico.core.common.inBounds
import com.patrykandpatrick.vico.core.common.unaryMinus
import java.text.DecimalFormat

/**
 * A [Decoration] that highlights an _x_ value.
 *
 * @property x returns the _x_ value.
 * @property line the [LineComponent] for the line.
 * @property labelComponent the label [TextComponent].
 * @property label returns the label text.
 * @property horizontalLabelPosition defines the horizontal position of the label.
 * @property verticalLabelPosition defines the vertical position of the label.
 * @property labelRotationDegrees the rotation of the label (in degrees).
 * @property horizontalAxisPosition the position of the [HorizontalAxis] whose scale the
 *   [VerticalLine] should use when interpreting [x]. Currently not used as x range is global.
 */
public class VerticalLine(
  private val x: (ExtraStore) -> Double,
  private val line: LineComponent,
  private val labelComponent: TextComponent? = null,
  private val label: (ExtraStore) -> CharSequence = { getLabel(x(it)) },
  private val horizontalLabelPosition: Position.Horizontal = Position.Horizontal.Start,
  private val verticalLabelPosition: Position.Vertical = Position.Vertical.Top,
  private val labelRotationDegrees: Float = 0f,
  private val horizontalAxisPosition: Axis.Position.Horizontal? = null,
) : Decoration {
  override fun drawOverLayers(context: CartesianDrawingContext) {
    with(context) {
      val x = x(model.extraStore)
      val label = label(model.extraStore)

            // Calculate canvas position using the same method as HorizontalAxis guidelines
      val baseCanvasX = layerBounds.left - scroll + layerDimensions.startPadding * layoutDirectionMultiplier
      val canvasX = baseCanvasX + ((x - ranges.minX) / ranges.xStep).toFloat() * layerDimensions.xSpacing * layoutDirectionMultiplier

      line.drawVertical(context, canvasX, layerBounds.top, layerBounds.bottom)
      if (labelComponent == null) return
      val clippingFreeVerticalLabelPosition =
        verticalLabelPosition.inBounds(
          bounds = layerBounds,
          componentHeight =
            labelComponent.getHeight(
              context = context,
              text = label,
              rotationDegrees = labelRotationDegrees,
            ),
          referenceY = layerBounds.centerY(),
          referenceDistance = line.thicknessDp.half.pixels,
        )
      labelComponent.draw(
        context = context,
        text = label,
        x =
          when (horizontalLabelPosition) {
            Position.Horizontal.Start -> canvasX - line.thicknessDp.half.pixels
            Position.Horizontal.Center -> canvasX
            Position.Horizontal.End -> canvasX + line.thicknessDp.half.pixels
          },
        y =
          when (clippingFreeVerticalLabelPosition) {
            Position.Vertical.Top -> layerBounds.top
            Position.Vertical.Center -> layerBounds.centerY()
            Position.Vertical.Bottom -> layerBounds.bottom
          },
        horizontalPosition = -horizontalLabelPosition,
        verticalPosition = clippingFreeVerticalLabelPosition,
        maxWidth = layerBounds.width().toInt(),
        rotationDegrees = labelRotationDegrees,
      )
    }
  }

  override fun equals(other: Any?): Boolean =
    this === other ||
      other is VerticalLine &&
        line == other.line &&
        labelComponent == other.labelComponent &&
        horizontalLabelPosition == other.horizontalLabelPosition &&
        verticalLabelPosition == other.verticalLabelPosition &&
        labelRotationDegrees == other.labelRotationDegrees

  override fun hashCode(): Int {
    var result = x.hashCode()
    result = 31 * result + line.hashCode()
    result = 31 * result + labelComponent.hashCode()
    result = 31 * result + label.hashCode()
    result = 31 * result + horizontalLabelPosition.hashCode()
    result = 31 * result + verticalLabelPosition.hashCode()
    result = 31 * result + labelRotationDegrees.hashCode()
    return result
  }

  /** @suppress */
  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
  public companion object {
    private val decimalFormat: DecimalFormat = DecimalFormat("#.##;−#.##")

    public fun getLabel(x: Double): String = decimalFormat.format(x)
  }
}

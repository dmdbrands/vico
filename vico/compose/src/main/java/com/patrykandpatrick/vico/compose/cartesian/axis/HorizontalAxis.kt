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

package com.patrykandpatrick.vico.compose.cartesian.axis

import android.R.attr.label
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.core.cartesian.axis.Axis
import com.patrykandpatrick.vico.core.cartesian.axis.BaseAxis
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.common.Defaults
import com.patrykandpatrick.vico.core.common.Position
import com.patrykandpatrick.vico.core.common.component.LineComponent
import com.patrykandpatrick.vico.core.common.component.TextComponent
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import java.nio.file.Files.size

/** Creates and remembers a top [HorizontalAxis]. */
@Composable
public fun HorizontalAxis.Companion.rememberTop(
  line: LineComponent? = rememberAxisLineComponent(),
  label: TextComponent? = rememberAxisLabelComponent(),
  labelRotationDegrees: Float = Defaults.AXIS_LABEL_ROTATION_DEGREES,
  valueFormatter: CartesianValueFormatter = CartesianValueFormatter.Default,
  tick: LineComponent? = rememberAxisTickComponent(),
  tickLength: Dp = Defaults.AXIS_TICK_LENGTH.dp,
  guideline: LineComponent? = rememberAxisGuidelineComponent(),
  itemPlacer: HorizontalAxis.ItemPlacer = remember { HorizontalAxis.ItemPlacer.aligned() },
  size: BaseAxis.Size = BaseAxis.Size.auto(),
  titleComponent: TextComponent? = null,
  title: CharSequence? = null,
  separators: (ExtraStore) -> List<Double> = { emptyList() },
  horizontalLabelPosition: Position.Horizontal = Position.Horizontal.Center,
): HorizontalAxis<Axis.Position.Horizontal.Top> =
  remember(
    line,
    label,
    labelRotationDegrees,
    valueFormatter,
    tick,
    tickLength.value,
    guideline,
    itemPlacer,
    size,
    titleComponent,
    title,
    separators,
    horizontalLabelPosition
  ) {
    top(
      line,
      label,
      labelRotationDegrees,
      valueFormatter,
      tick,
      tickLength.value,
      guideline,
      itemPlacer,
      size,
      titleComponent,
      title,
      separators,
      horizontalLabelPosition

    )
  }

/** Creates and remembers a bottom [HorizontalAxis]. */
@Composable
public fun HorizontalAxis.Companion.rememberBottom(
  line: LineComponent? = rememberAxisLineComponent(),
  label: TextComponent? = rememberAxisLabelComponent(),
  labelRotationDegrees: Float = Defaults.AXIS_LABEL_ROTATION_DEGREES,
  valueFormatter: CartesianValueFormatter = CartesianValueFormatter.Default,
  tick: LineComponent? = rememberAxisTickComponent(),
  tickLength: Dp = Defaults.AXIS_TICK_LENGTH.dp,
  guideline: LineComponent? = rememberAxisGuidelineComponent(),
  itemPlacer: HorizontalAxis.ItemPlacer = remember { HorizontalAxis.ItemPlacer.aligned() },
  size: BaseAxis.Size = BaseAxis.Size.auto(),
  titleComponent: TextComponent? = null,
  title: CharSequence? = null,
  separators: (ExtraStore) -> List<Double> = { emptyList() },
  horizontalLabelPosition: Position.Horizontal = Position.Horizontal.Center,
): HorizontalAxis<Axis.Position.Horizontal.Bottom> =
  remember(
    line,
    label,
    labelRotationDegrees,
    valueFormatter,
    tick,
    tickLength.value,
    guideline,
    itemPlacer,
    size,
    titleComponent,
    title,
    separators,
    horizontalLabelPosition
  ) {
    bottom(
      line,
      label,
      labelRotationDegrees,
      valueFormatter,
      tick,
      tickLength.value,
      guideline,
      itemPlacer,
      size,
      titleComponent,
      title,
      separators,
      horizontalLabelPosition
    )
  }

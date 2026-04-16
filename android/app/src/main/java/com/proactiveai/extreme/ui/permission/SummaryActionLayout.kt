package com.proactiveai.extreme.ui.permission

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.proactiveai.extreme.ui.theme.Gold200
import com.proactiveai.extreme.ui.theme.Gold500
import com.proactiveai.extreme.ui.theme.Sky700
import com.proactiveai.extreme.ui.theme.Slate200
import com.proactiveai.extreme.ui.theme.Slate500
import com.proactiveai.extreme.ui.theme.Slate700
import com.proactiveai.extreme.ui.theme.Slate800
import com.proactiveai.extreme.ui.theme.Slate900
import com.proactiveai.extreme.ui.theme.White

internal fun <T> chunkForCompactActionGrid(
    items: List<T>,
    columns: Int = 2,
): List<List<T>> {
    require(columns > 0) { "columns must be greater than 0" }
    return items.chunked(columns)
}

internal fun chunkActionLabelsForCompactGrid(labels: List<String>): List<List<String>> {
    return chunkForCompactActionGrid(labels)
}

internal data class CompactActionButtonSpec(
    val label: String,
    val onClick: () -> Unit,
    val outlined: Boolean = true,
    val enabled: Boolean = true,
)

internal enum class ActionSurfaceTone {
    Standard,
    Dark,
}

internal data class CompactActionPalette(
    val filledContainerColor: Color,
    val filledContentColor: Color,
    val filledDisabledContainerColor: Color,
    val filledDisabledContentColor: Color,
    val outlinedBorderColor: Color,
    val outlinedDisabledBorderColor: Color,
    val outlinedContentColor: Color,
    val outlinedDisabledContentColor: Color,
)

internal fun compactActionPalette(tone: ActionSurfaceTone): CompactActionPalette = when (tone) {
    ActionSurfaceTone.Standard -> CompactActionPalette(
        filledContainerColor = Sky700,
        filledContentColor = White,
        filledDisabledContainerColor = Slate200,
        filledDisabledContentColor = Slate500,
        outlinedBorderColor = Slate200,
        outlinedDisabledBorderColor = Slate200,
        outlinedContentColor = Slate700,
        outlinedDisabledContentColor = Slate500,
    )
    ActionSurfaceTone.Dark -> CompactActionPalette(
        filledContainerColor = Gold500,
        filledContentColor = Slate900,
        filledDisabledContainerColor = Slate800,
        filledDisabledContentColor = Gold200.copy(alpha = 0.58f),
        outlinedBorderColor = Gold200,
        outlinedDisabledBorderColor = Gold200.copy(alpha = 0.26f),
        outlinedContentColor = Gold200,
        outlinedDisabledContentColor = Slate700,
    )
}

@Composable
internal fun CompactActionButtonGrid(
    actions: List<CompactActionButtonSpec>,
    columns: Int = 2,
    surfaceTone: ActionSurfaceTone = ActionSurfaceTone.Standard,
) {
    val palette = compactActionPalette(surfaceTone)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        chunkForCompactActionGrid(actions, columns).forEach { rowActions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowActions.forEach { action ->
                    if (action.outlined) {
                        OutlinedButton(
                            onClick = action.onClick,
                            enabled = action.enabled,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            border = BorderStroke(
                                1.dp,
                                if (action.enabled) {
                                    palette.outlinedBorderColor.copy(alpha = if (surfaceTone == ActionSurfaceTone.Dark) 0.6f else 0.55f)
                                } else {
                                    palette.outlinedDisabledBorderColor
                                },
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = palette.outlinedContentColor,
                                disabledContentColor = palette.outlinedDisabledContentColor,
                            ),
                        ) {
                            Text(
                                text = action.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                softWrap = false,
                            )
                        }
                    } else {
                        Button(
                            onClick = action.onClick,
                            enabled = action.enabled,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = palette.filledContainerColor,
                                contentColor = palette.filledContentColor,
                                disabledContainerColor = palette.filledDisabledContainerColor,
                                disabledContentColor = palette.filledDisabledContentColor,
                            ),
                        ) {
                            Text(
                                text = action.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                softWrap = false,
                            )
                        }
                    }
                }
                repeat(columns - rowActions.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

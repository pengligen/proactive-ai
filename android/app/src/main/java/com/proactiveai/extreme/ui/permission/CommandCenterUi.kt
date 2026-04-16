package com.proactiveai.extreme.ui.permission

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.proactiveai.extreme.ui.theme.Amber50
import com.proactiveai.extreme.ui.theme.Amber600
import com.proactiveai.extreme.ui.theme.Emerald50
import com.proactiveai.extreme.ui.theme.Emerald500
import com.proactiveai.extreme.ui.theme.Gold200
import com.proactiveai.extreme.ui.theme.Gold50
import com.proactiveai.extreme.ui.theme.Gold500
import com.proactiveai.extreme.ui.theme.Rose50
import com.proactiveai.extreme.ui.theme.Rose600
import com.proactiveai.extreme.ui.theme.Sky50
import com.proactiveai.extreme.ui.theme.Sky700
import com.proactiveai.extreme.ui.theme.Slate50
import com.proactiveai.extreme.ui.theme.Slate100
import com.proactiveai.extreme.ui.theme.Slate200
import com.proactiveai.extreme.ui.theme.Slate600
import com.proactiveai.extreme.ui.theme.Slate700
import com.proactiveai.extreme.ui.theme.Slate800
import com.proactiveai.extreme.ui.theme.Slate900
import com.proactiveai.extreme.ui.theme.White

internal enum class SignalTone {
    Neutral,
    Info,
    Success,
    Warning,
    Danger,
}

internal enum class PanelTone {
    Standard,
    Hero,
}

private val panelShape = RoundedCornerShape(24.dp)
private val insetPanelShape = RoundedCornerShape(18.dp)
private val pillShape = RoundedCornerShape(999.dp)

internal data class PanelChrome(
    val containerColor: Color,
    val contentColor: Color,
    val subtleColor: Color,
    val borderColor: Color,
    val insetColor: Color,
    val eyebrowColor: Color,
)

internal fun panelChrome(tone: PanelTone): PanelChrome = when (tone) {
    PanelTone.Standard -> PanelChrome(
        containerColor = White,
        contentColor = Slate900,
        subtleColor = Slate600,
        borderColor = Slate200,
        insetColor = Slate50,
        eyebrowColor = Sky700,
    )
    PanelTone.Hero -> PanelChrome(
        containerColor = Slate900,
        contentColor = Gold50,
        subtleColor = Gold200,
        borderColor = Gold500,
        insetColor = Slate800,
        eyebrowColor = Gold500,
    )
}

internal data class SignalPillColors(
    val background: Color,
    val content: Color,
)

internal fun signalPillColors(tone: SignalTone): SignalPillColors = when (tone) {
    SignalTone.Neutral -> SignalPillColors(background = Slate100, content = Slate700)
    SignalTone.Info -> SignalPillColors(background = Sky50, content = Sky700)
    SignalTone.Success -> SignalPillColors(background = Emerald50, content = Emerald500)
    SignalTone.Warning -> SignalPillColors(background = Amber50, content = Amber600)
    SignalTone.Danger -> SignalPillColors(background = Rose50, content = Rose600)
}

@Composable
internal fun CommandCenterPanel(
    modifier: Modifier = Modifier,
    tone: PanelTone = PanelTone.Standard,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val chrome = panelChrome(tone)
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = panelShape,
        colors = CardDefaults.cardColors(
            containerColor = chrome.containerColor,
            contentColor = chrome.contentColor,
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = chrome.borderColor.copy(alpha = if (tone == PanelTone.Hero) 0.72f else 0.55f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
internal fun CommandCenterInsetPanel(
    modifier: Modifier = Modifier,
    tone: PanelTone = PanelTone.Standard,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val chrome = panelChrome(tone)
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = insetPanelShape,
        colors = CardDefaults.cardColors(
            containerColor = chrome.insetColor,
            contentColor = chrome.contentColor,
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = chrome.borderColor.copy(alpha = if (tone == PanelTone.Hero) 0.48f else 0.35f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
internal fun PanelHeader(
    title: String,
    subtitle: String? = null,
    tone: PanelTone = PanelTone.Standard,
    action: (@Composable () -> Unit)? = null,
) {
    val chrome = panelChrome(tone)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = chrome.subtleColor,
                )
            }
        }
        if (action != null) {
            Box(
                modifier = Modifier.padding(start = 12.dp),
                contentAlignment = Alignment.TopEnd,
            ) {
                action()
            }
        }
    }
}

@Composable
internal fun SignalPill(
    text: String,
    tone: SignalTone = SignalTone.Neutral,
) {
    val colors = signalPillColors(tone)
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = colors.content,
        modifier = Modifier
            .background(color = colors.background, shape = pillShape)
            .border(width = 1.dp, color = colors.content.copy(alpha = 0.1f), shape = pillShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
internal fun SignalPillRow(items: List<Pair<String, SignalTone>>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { (text, tone) ->
            SignalPill(text = text, tone = tone)
        }
    }
}

@Composable
internal fun InfoSurface(
    modifier: Modifier = Modifier,
    tone: PanelTone = PanelTone.Standard,
    content: @Composable ColumnScope.() -> Unit,
) {
    val chrome = panelChrome(tone)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = chrome.insetColor,
                shape = RoundedCornerShape(16.dp),
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
internal fun SignalValueRow(
    label: String,
    value: String,
    toneContainer: PanelTone = PanelTone.Standard,
    tone: SignalTone = SignalTone.Neutral,
) {
    val chrome = panelChrome(toneContainer)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = chrome.subtleColor,
        )
        SignalPill(text = value, tone = tone)
    }
}

@Composable
internal fun SectionEyebrow(
    text: String,
    tone: PanelTone = PanelTone.Standard,
) {
    val chrome = panelChrome(tone)
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = chrome.eyebrowColor,
        modifier = Modifier.widthIn(min = 0.dp),
    )
}

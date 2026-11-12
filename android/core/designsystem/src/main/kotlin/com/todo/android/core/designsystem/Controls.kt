package com.todo.android.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * 快速添加：点 `+` 进新建详情，回车按默认参数直接创建。
 * 两条路径默认值必须一致（开始=创建时刻、截止=+7 天、优先级中、当前清单）。
 */
@Composable
fun QuickAddRow(
    value: String,
    onValueChange: (String) -> Unit,
    onPlusClick: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "输入标题，回车快速创建（截止默认 7 天后）",
) {
    val colors = TodoTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Radius.md))
            .background(colors.card)
            .border(
                width = 1.dp,
                color = if (focused) colors.primary else colors.dashed,
                shape = RoundedCornerShape(Tokens.Radius.md),
            )
            .padding(start = 6.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(colors.primary)
                .clickable(onClick = onPlusClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Add, contentDescription = "新建任务", tint = colors.onPrimary, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(Tokens.Space.sm))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            textStyle = Tokens.Type.taskTitle.copy(color = colors.text),
            singleLine = true,
            interactionSource = interaction,
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(hint, style = Tokens.Type.body, color = colors.text3, maxLines = 1)
                }
                inner()
            },
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(Tokens.Radius.xs))
                .background(if (colors.isDark) colors.border2 else androidx.compose.ui.graphics.Color(0xFFF1F5F9))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text("↵", style = Tokens.Type.mono, color = colors.text3)
        }
    }
}

@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TodoTheme.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Tokens.Radius.sm))
            .background(if (colors.isDark) colors.border2 else androidx.compose.ui.graphics.Color(0xFFF1F5F9))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val active = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(Tokens.Radius.sm - 2.dp))
                    .background(if (active) colors.card else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelected(index) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = Tokens.Type.chip,
                    color = if (active) colors.text else colors.text3,
                )
            }
        }
    }
}

@Composable
fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = TodoTheme.colors
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title.uppercase(),
            style = Tokens.Type.badge,
            color = colors.text3,
            modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Tokens.Radius.lg))
                .background(colors.card)
                .border(1.dp, colors.border, RoundedCornerShape(Tokens.Radius.lg)),
        ) {
            content()
        }
    }
}

@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = TodoTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = Tokens.Type.body,
                color = if (danger) colors.danger else colors.text,
            )
            if (description != null) {
                Spacer(Modifier.height(2.dp))
                Text(text = description, style = Tokens.Type.meta, color = colors.text3)
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun SettingDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(TodoTheme.colors.border2),
    )
}

@Composable
fun TodoSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val colors = TodoTheme.colors
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = colors.card,
            checkedTrackColor = colors.primary,
            uncheckedThumbColor = colors.text3,
            uncheckedTrackColor = colors.track,
        ),
    )
}

@Composable
fun StatusBadge(text: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Tokens.Radius.full))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text = text, style = Tokens.Type.badge, color = fg)
    }
}

package com.todo.android.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 行内危险提示：替代「确认删除」类弹窗。
 *
 * 放在触发按钮上方，配合按钮文案切换（删除 → 确认删除、执行 → 确认执行）实现两步确认，
 * 与 Windows 端详情抽屉的 `delete-hint` 行为一致，不再打断用户。
 */
@Composable
fun InlineWarning(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = TodoTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Radius.sm))
            .background(colors.danger50)
            .border(1.dp, colors.dangerBorder, RoundedCornerShape(Tokens.Radius.sm))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "!",
            style = Tokens.Type.chip.copy(fontWeight = FontWeight.Bold),
            color = colors.danger,
        )
        Text(text, style = Tokens.Type.meta, color = colors.danger)
    }
}

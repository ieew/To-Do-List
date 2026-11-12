package com.todo.android.core.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

/** 单行编辑对话框；`onConfirm` 返回 false 时保留对话框并提示。 */
@Composable
fun EditDialog(
    title: String,
    initial: String,
    placeholder: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Boolean,
    keyboardType: KeyboardType = KeyboardType.Text,
    description: String? = null,
) {
    val colors = TodoTheme.colors
    var value by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.card,
        title = { Text(title, style = Tokens.Type.pageTitle, color = colors.text) },
        text = {
            Column {
                if (description != null) {
                    Text(description, style = Tokens.Type.meta, color = colors.text3)
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(placeholder, style = Tokens.Type.body, color = colors.text3) },
                    singleLine = true,
                    textStyle = Tokens.Type.body.copy(color = colors.text),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = colors.card,
                        unfocusedContainerColor = colors.card,
                        focusedTextColor = colors.text,
                        unfocusedTextColor = colors.text,
                        cursorColor = colors.primary,
                    ),
                )
                if (error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(error.orEmpty(), style = Tokens.Type.meta, color = colors.danger)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = value.trim()
                    if (trimmed.isEmpty()) {
                        error = "不能为空"
                    } else if (!onConfirm(trimmed)) {
                        error = "输入不合法（1–24 字符）"
                    }
                },
            ) {
                Text(confirmLabel, color = colors.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = colors.text2)
            }
        },
    )
}

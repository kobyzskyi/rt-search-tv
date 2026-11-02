package dev.rtsearch.tv.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * A text field with a clearly visible focus border for remote control navigation.
 *
 * Moving focus onto the field only highlights it; the on-screen keyboard opens when the user presses OK.
 * Otherwise every D-pad pass over a field would pop the keyboard up.
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    onAction: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val input = remember { FocusRequester() }
    val shape = RoundedCornerShape(10.dp)

    LaunchedEffect(editing) {
        if (editing) runCatching { input.requestFocus() }
    }

    // The outer box takes D-pad focus; OK on it hands focus to the real text field, which shows the keyboard.
    Box(
        modifier
            .onFocusChanged {
                focused = it.hasFocus
                if (!it.hasFocus) editing = false
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { editing = true },
    ) {
        TextInput(value, onValueChange, placeholder, password, imeAction, onAction, focused, shape, input, editing)
    }
}

@Composable
private fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    password: Boolean,
    imeAction: ImeAction,
    onAction: () -> Unit,
    focused: Boolean,
    shape: RoundedCornerShape,
    input: FocusRequester,
    editing: Boolean,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 20.sp),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else KeyboardType.Text,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(onAny = { onAction() }),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(input)
            .focusProperties { canFocus = editing },
        decorationBox = { inner ->
            Box(
                Modifier
                    .background(Color(0xFF1B2433), shape)
                    .border(
                        width = if (focused) 3.dp else 1.dp,
                        color = if (focused) MaterialTheme.colorScheme.primary else Color(0xFF3A475C),
                        shape = shape,
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (value.isEmpty()) Text(placeholder, color = Color(0xFF7D8BA0), fontSize = 20.sp)
                inner()
            }
        },
    )
}

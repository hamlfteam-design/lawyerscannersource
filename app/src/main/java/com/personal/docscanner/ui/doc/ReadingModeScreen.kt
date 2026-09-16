package com.personal.docscanner.ui.doc

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.personal.docscanner.R

private const val MIN_FONT_SCALE = 0.85f
private const val MAX_FONT_SCALE = 1.8f
private const val FONT_STEP = 0.15f

/**
 * The raw OCR dump the document screen shows is exactly that: one run-on
 * block in a small card, fine for confirming OCR found something but not for
 * actually reading a page of a contract. This is the same text full-screen,
 * reflowed at a comfortable size with the line spacing a source-code-shaped
 * `bodyMedium` never bothered with, and a size control for print that would
 * otherwise be too small for anyone who needed reading glasses for the
 * original paper.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingModeScreen(text: String, onDismiss: () -> Unit) {
    var fontScale by remember { mutableFloatStateOf(1f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text(stringResource(R.string.reading_mode)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, stringResource(R.string.cancel))
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = { fontScale = (fontScale - FONT_STEP).coerceAtLeast(MIN_FONT_SCALE) },
                            enabled = fontScale > MIN_FONT_SCALE
                        ) { Text("A-") }
                        TextButton(
                            onClick = { fontScale = (fontScale + FONT_STEP).coerceAtMost(MAX_FONT_SCALE) },
                            enabled = fontScale < MAX_FONT_SCALE
                        ) { Text("A+") }
                    }
                )

                SelectionContainer(Modifier.weight(1f)) {
                    Text(
                        text = text,
                        textAlign = TextAlign.Start,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = MaterialTheme.typography.bodyLarge.fontSize * fontScale,
                            lineHeight = 30.sp * fontScale
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 16.dp)
                    )
                }
            }
        }
    }
}

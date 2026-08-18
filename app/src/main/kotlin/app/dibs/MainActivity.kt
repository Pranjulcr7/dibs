// SPDX-License-Identifier: Apache-2.0
package app.dibs

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.dibs.ui.theme.DibsTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DibsTheme {
                PlaceholderScreen()
            }
        }
    }
}

@Composable
fun PlaceholderScreen(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.placeholder_coming_soon),
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

@Preview(name = "Light", showBackground = true)
@Composable
private fun PlaceholderScreenPreviewLight() {
    DibsTheme(darkTheme = false) { PlaceholderScreen() }
}

@Preview(name = "Dark", showBackground = true, uiMode = 0x21)
@Composable
private fun PlaceholderScreenPreviewDark() {
    DibsTheme(darkTheme = true) { PlaceholderScreen() }
}

@Preview(name = "Large font", showBackground = true, fontScale = 2.0f)
@Composable
private fun PlaceholderScreenPreviewLargeFont() {
    DibsTheme { PlaceholderScreen() }
}

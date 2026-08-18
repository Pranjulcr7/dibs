// SPDX-License-Identifier: Apache-2.0
package app.dibs

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by viewModel.uiState.collectAsState()
            when (val current = state) {
                // A blank first frame beats flashing the wrong screen while the
                // stored settings load (they arrive within a frame or two).
                MainUiState.Loading -> Unit
                is MainUiState.Ready -> DibsApp(current.settings)
            }
        }
    }
}

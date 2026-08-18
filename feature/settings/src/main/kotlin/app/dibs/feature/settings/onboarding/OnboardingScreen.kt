// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.settings.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import app.dibs.core.ui.theme.DibsTheme
import app.dibs.core.ui.theme.Dimens
import app.dibs.feature.settings.R
import kotlinx.coroutines.launch

/** Route wrapper: owns the ViewModel; the screen itself stays testable with plain lambdas. */
@Composable
fun OnboardingRoute(onDone: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    OnboardingScreen(onFinish = { name ->
        viewModel.complete(name)
        onDone()
    })
}

private const val PAGE_COUNT = 3

/**
 * Three pages, skippable from anywhere, no permissions requested (SPEC §4.1
 * screen 11). [onFinish] receives the typed name, or null when skipped or left
 * empty.
 */
@Composable
fun OnboardingScreen(onFinish: (String?) -> Unit, modifier: Modifier = Modifier) {
    val pagerState = rememberPagerState { PAGE_COUNT }
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }

    fun finish(withName: String?) = onFinish(withName?.trim()?.takeUnless { it.isEmpty() })

    Scaffold(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(Dimens.spacingLg),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { finish(null) }) {
                    Text(stringResource(R.string.onboarding_skip))
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { page ->
                when (page) {
                    0 -> OnboardingPage(
                        title = stringResource(R.string.onboarding_pitch_title),
                        body = stringResource(R.string.onboarding_pitch_body),
                    )
                    1 -> OnboardingPage(
                        title = stringResource(R.string.onboarding_local_title),
                        body = stringResource(R.string.onboarding_local_body),
                    )
                    else -> OnboardingPage(
                        title = stringResource(R.string.onboarding_name_title),
                        body = stringResource(R.string.onboarding_name_body),
                    ) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text(stringResource(R.string.onboarding_name_label)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("onboarding-name"),
                        )
                    }
                }
            }

            PageIndicator(current = pagerState.currentPage)
            Spacer(Modifier.height(Dimens.spacingMd))

            val lastPage = pagerState.currentPage == PAGE_COUNT - 1
            Button(
                onClick = {
                    if (lastPage) {
                        finish(name)
                    } else {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (lastPage) R.string.onboarding_get_started else R.string.onboarding_next,
                    ),
                )
            }
        }
    }
}

@Composable
private fun OnboardingPage(title: String, body: String, content: @Composable () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = Dimens.spacingLg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Dimens.spacingMd))
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Dimens.spacingLg))
        content()
    }
}

@Composable
private fun PageIndicator(current: Int) {
    val description = stringResource(R.string.onboarding_page_of, current + 1, PAGE_COUNT)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm, Alignment.CenterHorizontally),
    ) {
        repeat(PAGE_COUNT) { index ->
            val active = index == current
            Box(
                modifier = Modifier
                    .size(if (active) Dimens.spacingSm + Dimens.spacingXs else Dimens.spacingSm)
                    .background(
                        color = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = CircleShape,
                    ),
            )
        }
    }
}

@Preview(name = "Light", showBackground = true)
@Composable
private fun OnboardingPreviewLight() {
    DibsTheme { OnboardingScreen(onFinish = {}) }
}

@Preview(name = "Dark", showBackground = true, uiMode = 0x21)
@Composable
private fun OnboardingPreviewDark() {
    DibsTheme { OnboardingScreen(onFinish = {}) }
}

@Preview(name = "Large font", showBackground = true, fontScale = 2.0f)
@Composable
private fun OnboardingPreviewLargeFont() {
    DibsTheme { OnboardingScreen(onFinish = {}) }
}

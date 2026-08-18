// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.dibs.core.domain.model.MemberId
import app.dibs.core.ui.theme.DibsTheme

// A fixed palette; the pick is keyed to the member id so the same person keeps
// the same color everywhere — this is what disambiguates two members with the
// same name (SPEC §6.2 case 12).
private val AvatarColors = listOf(
    Color(0xFF8B5000),
    Color(0xFF00696E),
    Color(0xFF735086),
    Color(0xFF3A6B35),
    Color(0xFF87521F),
    Color(0xFF00639B),
    Color(0xFF9A4058),
    Color(0xFF5C6237),
)

/** Circular initial avatar with a color derived deterministically from [memberId]. */
@Composable
fun MemberAvatar(name: String, memberId: MemberId, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val color = AvatarColors[(memberId.value.hashCode().mod(AvatarColors.size))]
    Box(
        modifier = modifier
            .size(size)
            .background(color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().firstOrNull()?.uppercase() ?: "?",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Preview(name = "Light", showBackground = true)
@Composable
private fun MemberAvatarPreviewLight() {
    DibsTheme { MemberAvatar(name = "Alice", memberId = MemberId("0a-1")) }
}

@Preview(name = "Dark", showBackground = true, uiMode = 0x21)
@Composable
private fun MemberAvatarPreviewDark() {
    DibsTheme { MemberAvatar(name = "Bob", memberId = MemberId("0b-2")) }
}

@Preview(name = "Large font", showBackground = true, fontScale = 2.0f)
@Composable
private fun MemberAvatarPreviewLargeFont() {
    DibsTheme { MemberAvatar(name = "Carol", memberId = MemberId("0c-3")) }
}

package io.github.andrealtb.coloroslyrics.provider.universal.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.andrealtb.coloroslyrics.provider.universal.R
import io.github.andrealtb.coloroslyrics.provider.universal.UniversalAppIo
import io.github.andrealtb.coloroslyrics.provider.universal.UniversalFeaturePrefs
import io.github.andrealtb.coloroslyrics.provider.universal.api.AppleMusicAuth
import io.github.andrealtb.coloroslyrics.provider.universal.api.AppleMusicAuthState
import kotlinx.coroutines.withContext
import java.util.Locale

/** The TTML-first switch and the Apple Music source mode, below the lyric processing switches. */
@Composable
fun LyricFeatureSettings() {
    val context = LocalContext.current
    var loaded by remember { mutableStateOf(false) }
    var ttmlFirst by remember { mutableStateOf(false) }
    var auth by remember { mutableStateOf(AppleMusicAuth()) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) {
        val (ttml, current) = withContext(UniversalAppIo.dispatcher) {
            UniversalFeaturePrefs.ttmlFirst(context) to AppleMusicAuthState.current
        }
        ttmlFirst = ttml
        auth = current
        loaded = true
        AppleMusicTokenRefresher.refreshIfNeeded(context)
    }
    // Coming back from the sign-in page picks up the new token.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    fun updateAuth(transform: (AppleMusicAuth) -> AppleMusicAuth) {
        auth = transform(auth)
        UniversalAppIo.execute { UniversalFeaturePrefs.updateAppleMusic(context.applicationContext, transform) }
    }

    Text(
        stringResource(R.string.section_lyric_format),
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
    )
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 8.dp, bottomEnd = 8.dp)),
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            leadingContent = { Icon(Icons.Filled.Code, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            headlineContent = { Text(stringResource(R.string.ttml_first), style = MaterialTheme.typography.bodyLarge) },
            supportingContent = { Text(stringResource(R.string.ttml_first_desc), style = MaterialTheme.typography.bodySmall) },
            trailingContent = {
                Switch(
                    checked = ttmlFirst,
                    enabled = loaded,
                    onCheckedChange = { enabled ->
                        ttmlFirst = enabled
                        UniversalAppIo.execute { UniversalFeaturePrefs.setTtmlFirst(context.applicationContext, enabled) }
                    }
                )
            }
        )

        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .clip(RoundedCornerShape(8.dp)),
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            leadingContent = { Icon(Icons.Filled.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            headlineContent = { Text(stringResource(R.string.am_official), style = MaterialTheme.typography.bodyLarge) },
            supportingContent = { Text(stringResource(R.string.am_official_desc), style = MaterialTheme.typography.bodySmall) },
            trailingContent = {
                Switch(
                    checked = auth.official,
                    enabled = loaded,
                    onCheckedChange = { enabled ->
                        updateAuth {
                            val retry = enabled && it.status == AppleMusicAuth.STATUS_NO_SUBSCRIPTION
                            it.copy(official = enabled, status = if (retry) AppleMusicAuth.STATUS_OK else it.status)
                        }
                    }
                )
            }
        )

        val signedIn = auth.mediaUserToken.isNotBlank()
        val statusText = when {
            !signedIn -> stringResource(R.string.am_status_signed_out)
            auth.status == AppleMusicAuth.STATUS_REJECTED -> stringResource(R.string.am_status_rejected)
            auth.status == AppleMusicAuth.STATUS_NO_SUBSCRIPTION ->
                stringResource(R.string.am_status_no_subscription, auth.storefront().uppercase(Locale.ROOT))
            else -> stringResource(R.string.am_status_signed_in, auth.storefront().uppercase(Locale.ROOT))
        }
        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .clip(RoundedCornerShape(8.dp)),
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            leadingContent = { Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            headlineContent = { Text(stringResource(R.string.am_account), style = MaterialTheme.typography.bodyLarge) },
            supportingContent = { Text(statusText, style = MaterialTheme.typography.bodySmall) },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (signedIn) {
                        TextButton(onClick = {
                            AppleMusicWebToken.clearCookies()
                            updateAuth { it.copy(mediaUserToken = "", status = AppleMusicAuth.STATUS_MISSING, tokenUpdatedAt = 0L) }
                        }) { Text(stringResource(R.string.am_sign_out)) }
                    }
                    TextButton(
                        enabled = loaded,
                        onClick = {
                            // The UI context is wrapped for the in-app language and is not an Activity.
                            context.startActivity(
                                Intent(context, AppleMusicLoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    ) {
                        Text(stringResource(if (signedIn) R.string.am_sign_in_again else R.string.am_sign_in))
                    }
                }
            }
        )

        var regionMenu by remember { mutableStateOf(false) }
        val autoLabel = stringResource(R.string.am_region_auto)
        val regionText = if (auth.storefrontOverride.isBlank()) {
            autoLabel + " · " + auth.storefront().uppercase(Locale.ROOT)
        } else {
            auth.storefrontOverride.uppercase(Locale.ROOT)
        }
        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 28.dp, bottomEnd = 28.dp)),
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            leadingContent = { Icon(Icons.Filled.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            headlineContent = { Text(stringResource(R.string.am_region), style = MaterialTheme.typography.bodyLarge) },
            supportingContent = {
                Text(
                    stringResource(R.string.am_region_desc, auth.language()),
                    style = MaterialTheme.typography.bodySmall
                )
            },
            trailingContent = {
                TextButton(enabled = loaded, onClick = { regionMenu = true }) { Text(regionText) }
                DropdownMenu(expanded = regionMenu, onDismissRequest = { regionMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(autoLabel) },
                        onClick = {
                            regionMenu = false
                            updateAuth { it.copy(storefrontOverride = "") }
                        }
                    )
                    AppleMusicAuth.STOREFRONTS.forEach { code ->
                        DropdownMenuItem(
                            text = { Text(code.uppercase(Locale.ROOT)) },
                            onClick = {
                                regionMenu = false
                                updateAuth { it.copy(storefrontOverride = code) }
                            }
                        )
                    }
                }
            }
        )
    }
}

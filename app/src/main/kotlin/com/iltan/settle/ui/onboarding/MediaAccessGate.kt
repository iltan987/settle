package com.iltan.settle.ui.onboarding

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.iltan.settle.R
import com.iltan.settle.data.media.MediaAccess

/**
 * Shows [content] only with full media access; otherwise explains why it's needed and asks. Access
 * is read again on every resume, since the user can change it in system settings at any time.
 */
@Composable
fun MediaAccessGate(onAccessGranted: () -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var access by remember { mutableStateOf(MediaAccess.of(context)) }
    // True once a request came back denied and the system won't show its dialog again.
    var blocked by rememberSaveable { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        access = MediaAccess.of(context)
        onPauseOrDispose {}
    }
    val launcher =
        rememberLauncherForActivityResult(RequestMultiplePermissions()) {
            access = MediaAccess.of(context)
            blocked =
                access == MediaAccess.NONE &&
                    activity?.shouldShowRequestPermissionRationale(
                        Manifest.permission.READ_MEDIA_IMAGES
                    ) == false
            if (access == MediaAccess.FULL) onAccessGranted()
        }

    if (access == MediaAccess.FULL) {
        content()
    } else {
        OnboardingScreen(
            access = access,
            blocked = blocked,
            onRequest = { launcher.launch(MediaAccess.permissions) },
            onOpenSettings = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    )
                )
            },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OnboardingScreen(
    access: MediaAccess,
    blocked: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val partial = access == MediaAccess.PARTIAL
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.safeDrawingPadding().padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(112.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(R.drawable.ic_photo_library),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
            Text(
                stringResource(
                    if (partial) R.string.onboarding_partial_title else R.string.onboarding_title
                ),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(
                    when {
                        partial -> R.string.onboarding_partial_body
                        blocked -> R.string.onboarding_blocked_body
                        else -> R.string.onboarding_body
                    }
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (blocked) {
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = ButtonDefaults.MediumContentPadding,
                ) {
                    Text(stringResource(R.string.onboarding_open_settings))
                }
            } else {
                Button(
                    onClick = onRequest,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = ButtonDefaults.MediumContentPadding,
                ) {
                    Text(
                        stringResource(
                            if (partial) R.string.onboarding_allow_all
                            else R.string.onboarding_allow
                        )
                    )
                }
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.onboarding_open_settings))
                }
            }
        }
    }
}

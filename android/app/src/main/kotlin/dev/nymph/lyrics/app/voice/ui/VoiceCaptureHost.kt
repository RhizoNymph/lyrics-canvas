package dev.nymph.lyrics.app.voice.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.nymph.lyrics.app.di.AppContainer
import dev.nymph.lyrics.app.voice.PermissionAction
import dev.nymph.lyrics.app.voice.VoiceCaptureViewModel
import dev.nymph.lyrics.app.voice.deniedPermanently
import dev.nymph.lyrics.app.voice.permissionAction
import dev.nymph.lyrics.logic.voice.PermissionStatus
import dev.nymph.lyrics.logic.voice.RecorderState
import dev.nymph.lyrics.model.CanvasId
import java.io.File

private const val MIC = Manifest.permission.RECORD_AUDIO

/** A voice capture ViewModel in the current back-stack entry, distinguished by [key] (canvas vs editor). */
@Composable
fun voiceCaptureViewModel(container: AppContainer, canvasId: CanvasId, key: String): VoiceCaptureViewModel = viewModel(key = key) {
    VoiceCaptureViewModel(
        canvasId = canvasId,
        voice = container.voice,
        recorder = container.newRecorder(),
        settings = container.voiceSettings,
        clock = SystemClock::elapsedRealtime,
        deleteFile = { File(it.path).delete() },
    )
}

private fun Activity?.micGranted(): Boolean =
    this != null && ContextCompat.checkSelfPermission(this, MIC) == PackageManager.PERMISSION_GRANTED

private fun Activity?.micRationale(): Boolean = this != null && ActivityCompat.shouldShowRequestPermissionRationale(this, MIC)

/**
 * Shows the recording modal while [vm] is not idle and does the Android side of it: the
 * RECORD_AUDIO runtime permission (rationale, system dialog, permanently-denied path to the app
 * settings, re-check on return), and finishing a running take when the screen stops.
 */
@Composable
fun VoiceCaptureHost(vm: VoiceCaptureViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val silence by vm.silence.collectAsStateWithLifecycle()
    val vocabularyHints by vm.vocabularyHints.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val currentActivity by rememberUpdatedState(activity)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.permissionGranted() else vm.permissionDenied(deniedPermanently(currentActivity.micRationale()))
    }

    val permission = state as? RecorderState.Permission
    if (permission != null && permission.status == PermissionStatus.REQUESTING) {
        LaunchedEffect(permission.requestId) {
            when (permissionAction(activity.micGranted(), activity.micRationale(), firstAsk = permission.requestId == 1)) {
                PermissionAction.PROCEED -> vm.permissionGranted()
                PermissionAction.SHOW_RATIONALE -> vm.permissionRationaleNeeded()
                PermissionAction.LAUNCH_REQUEST -> launcher.launch(MIC)
            }
        }
    }

    // Back from the app settings (or another app) with the permission granted.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val p = vm.state.value as? RecorderState.Permission
        if (p != null && p.status != PermissionStatus.REQUESTING && currentActivity.micGranted()) vm.permissionGranted()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.hostStopped() }

    val shown = state
    if (shown !is RecorderState.Active) return
    VoiceCaptureDialog(
        state = shown,
        silence = silence,
        vocabularyHints = vocabularyHints,
        actions = VoiceCaptureActions(
            onTap = vm::tap,
            onStop = vm::stop,
            onCancel = vm::cancel,
            onRetry = vm::retry,
            onDiscard = vm::discard,
            onSilence = vm::setSilence,
            onVocabularyHints = vm::setVocabularyHints,
            onAskAgain = vm::requestPermissionAgain,
            onOpenSettings = {
                currentActivity?.let { a ->
                    a.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", a.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        ),
    )
}

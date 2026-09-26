package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.model.*
import com.kingzcheung.xime.speech.AsrModelManager
import com.kingzcheung.xime.speech.SpeechModelCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Download and select in the existing speech settings; selection applies to the next recording. */
@Composable
internal fun OfflineModelCard() {
    val context = LocalContext.current
    val manager = remember { AsrModelManager(context) }
    val models by ModelManager.modelsFlow.collectAsState()
    val downloads by ModelManager.downloadStates.collectAsState()
    var selected by remember { mutableStateOf(manager.getSelectedModelId()) }
    val error = downloads.values.filterIsInstance<ModelDownloadState.Error>().firstOrNull()?.message
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { ModelManager.loadFromRemote(context) } }
    val choices = listOf(SpeechModelCatalog.ZIPFORMER, SpeechModelCatalog.PARAFORMER, SpeechModelCatalog.SENSEVOICE)
    val firstPass = when (selected) {
        SpeechModelCatalog.TWO_PASS -> SpeechModelCatalog.PARAFORMER
        SpeechModelCatalog.ZIPFORMER_TWO_PASS -> SpeechModelCatalog.ZIPFORMER
        else -> selected
    }
    val refine = selected == SpeechModelCatalog.TWO_PASS || selected == SpeechModelCatalog.ZIPFORMER_TWO_PASS
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp).selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("离线语音模型", style = MaterialTheme.typography.titleMedium)
            Text("按停顿分段，标点转为空格。切换对下一次录音生效。", style = MaterialTheme.typography.bodyMedium)
            choices.forEach { id ->
                val info = manager.getAsrModels().firstOrNull { it.id == id }
                val isCorrection = id == SpeechModelCatalog.SENSEVOICE
                val state = downloads[id] as? ModelDownloadState.Downloading
                val ready = remember(id, downloads, models) { runCatching { manager.selection(id).ready }.getOrDefault(false) }
                val checked = if (isCorrection) refine else firstPass == id
                val title = if (isCorrection) "SenseVoice 二次校正" else info?.name.orEmpty()
                val detail = if (isCorrection) "对所选模型逐段复核，支持中英日粤韩；额外约 229 MB，较慢的设备可关闭。"
                    else info?.description.orEmpty()
                HorizontalDivider()
                val selectionModifier = if (isCorrection) Modifier.toggleable(value = checked, enabled = ready || checked,
                    role = Role.Switch, onValueChange = { manager.setRefinementEnabled(it); selected = manager.getSelectedModelId() })
                    else Modifier.selectable(selected = checked, enabled = ready, role = Role.RadioButton,
                        onClick = { manager.setFirstPassModel(id); selected = manager.getSelectedModelId() })
                Row(Modifier.fillMaxWidth().testTag("speech-choice:$id").then(selectionModifier), verticalAlignment = Alignment.CenterVertically) {
                    if (!isCorrection) RadioButton(selected = checked, enabled = ready, onClick = null)
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        Text(detail, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (isCorrection) Switch(checked = checked, enabled = ready || checked, onCheckedChange = null)
                    if (!ready && state == null) TextButton(onClick = {
                        ModelManager.getModel(id)?.let { ModelManager.downloadModelInBackground(context, it) }
                    }) { Text("下载") }
                }
                if (state != null) LinearProgressIndicator(progress = { state.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth())
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

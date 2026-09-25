package com.osone.app

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Locale

/** Aba Rotinas: o que o OSTIE faz sozinho em horários marcados, e o que já entregou. */
@Composable
fun RoutinesScreen(store: RoutineStore, diagnostics: AppDiagnostics, onDiagnostics: () -> Unit,
    onBack: () -> Unit, onRunNow: (Routine) -> Unit, onNeedNotifications: () -> Unit,
    calendarAccess: Boolean, onCalendar: () -> Unit, exactAlarms: Boolean = true, onExactAlarms: () -> Unit = {}) {
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<Routine?>(null) }
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val format = remember { SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        OstieTopBar(title = "Rotinas", subtitle = "${store.routines.count { it.enabled }} ativas",
            navigation = { BarIcon(OstieIcons.Back, "Voltar", onBack) }) {
            DiagnosticsDot(diagnostics, onDiagnostics)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                if (creating) RoutineForm(initial = null, onCancel = { creating = false }, onSave = { title, instruction, hour, minute, days, reminder ->
                    store.add(title, instruction, hour, minute, days, reminder)
                    creating = false
                    onNeedNotifications()
                }) else Button(onClick = { editing = null; creating = true }, modifier = Modifier.fillMaxWidth()) { Text("Nova rotina") }
            }
            // Sem "Alarmes e lembretes", o Android pode atrasar a rotina em até 10 minutos.
            if (!exactAlarms && store.routines.any { it.enabled }) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("As rotinas podem atrasar até 10 minutos. Libere \"Alarmes e lembretes\" para o horário exato.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onExactAlarms) { Text("Liberar") }
                }
            }
            if (!calendarAccess) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tarefas podem ler sua agenda, como num resumo da manhã.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = onCalendar) { Text("Permitir agenda") }
                }
            }
            if (store.routines.isEmpty() && !creating) item {
                Hint("Nenhuma rotina ainda. Crie aqui ou peça no Live: \"todo dia às 8h me diz minha agenda e as notícias\" ou \"me lembra às 18h de tomar o remédio\".")
            }
            items(store.routines, key = { it.id }) { routine ->
                if (editing == routine.id) RoutineForm(initial = routine, onCancel = { editing = null },
                    onSave = { title, instruction, hour, minute, days, reminder ->
                        store.update(routine.id, title, instruction, hour, minute, days, reminder)
                        editing = null
                    })
                else Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(routine.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text((if (routine.byEvent) "Ao chegar notificação: ${routine.onNotification}"
                                    else "${routine.timeLabel} · ${routine.daysLabel}") + " · ${if (routine.reminder) "lembrete" else "tarefa"}",
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                            Switch(checked = routine.enabled, onCheckedChange = { store.setEnabled(routine.id, it) })
                        }
                        Text(routine.instruction, style = MaterialTheme.typography.bodySmall, maxLines = 3,
                            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!routine.byEvent) Text(if (routine.enabled) "Próxima: " + RoutineSchedule.whenLabel(now,
                                RoutineSchedule.next(now, routine.hour, routine.minute, routine.days)) else "Pausada",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row {
                            TextButton(onClick = { onRunNow(routine) }) { Text("Testar agora") }
                            TextButton(onClick = { creating = false; editing = routine.id }) { Text("Editar") }
                            Spacer(Modifier.weight(1f))
                            BarIcon(OstieIcons.Delete, "Apagar rotina", { confirmDelete = routine },
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            if (store.history.isNotEmpty()) {
                item { Text("Últimos resultados", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                items(store.history) { (title, text, at) ->
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(start = 14.dp, top = 4.dp, end = 4.dp, bottom = 14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("$title · ${format.format(at)}", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                                BarIcon(OstieIcons.Copy, "Copiar resultado", {
                                    context.getSystemService(ClipboardManager::class.java)
                                        .setPrimaryClip(ClipData.newPlainText("OSTIE · $title", text))
                                    Toast.makeText(context, "Resultado copiado.", Toast.LENGTH_SHORT).show()
                                })
                            }
                            Text(text, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    confirmDelete?.let { routine ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text("Apagar \"${routine.title}\"?") },
            confirmButton = { TextButton(onClick = { store.remove(routine.id); confirmDelete = null }) { Text("Apagar") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancelar") } })
    }
}

@Composable
private fun RoutineForm(initial: Routine?, onCancel: () -> Unit,
    onSave: (title: String, instruction: String, hour: Int, minute: Int, days: Set<Int>, reminder: Boolean) -> Unit) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var instruction by remember { mutableStateOf(initial?.instruction ?: "") }
    var time by remember { mutableStateOf(initial?.timeLabel ?: "08:00") }
    var days by remember { mutableStateOf(initial?.days ?: emptySet()) }
    var reminder by remember { mutableStateOf(initial?.reminder ?: false) }
    val parsed = Regex("^([01]?\\d|2[0-3])[:h]([0-5]\\d)$").find(time.trim())
    SectionCard(if (initial == null) "Nova rotina" else "Editar rotina", OstieIcons.Settings) {
        OutlinedTextField(title, { title = it }, label = { Text("Nome") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
        OutlinedTextField(instruction, { instruction = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
            shape = MaterialTheme.shapes.medium,
            label = { Text(if (reminder) "Texto do lembrete" else "O que o OSTIE deve fazer") },
            placeholder = { Text(if (reminder) "Tomar o remédio" else "Resuma as principais notícias do Brasil e a previsão do tempo") })
        OutlinedTextField(time, { time = it.take(5) }, label = { Text("Horário (HH:MM)") }, singleLine = true,
            isError = parsed == null, modifier = Modifier.width(160.dp), shape = MaterialTheme.shapes.medium)
        Text("Dias (nenhum marcado = todos os dias)", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(1 to "D", 2 to "S", 3 to "T", 4 to "Q", 5 to "Q", 6 to "S", 7 to "S").forEach { (day, letter) ->
                FilterChip(selected = day in days, onClick = { days = if (day in days) days - day else days + day },
                    label = { Text(letter) }, modifier = Modifier.weight(1f))
            }
        }
        SettingSwitch("Só lembrete", reminder, { reminder = it },
            "Desligado: o modelo de texto executa a tarefa no horário (pode pesquisar na web) e manda o resultado.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val match = parsed ?: return@Button
                onSave(title, instruction, match.groupValues[1].toInt(), match.groupValues[2].toInt(), days, reminder)
            }, enabled = parsed != null && title.isNotBlank() && instruction.isNotBlank()) { Text("Salvar") }
            TextButton(onClick = onCancel) { Text("Cancelar") }
        }
    }
}

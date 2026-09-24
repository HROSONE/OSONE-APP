package com.osone.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Ajustes > Base de conhecimento: o que o OSTIE usa para atender sobre uma empresa, produto ou assunto. */
@Composable
fun KnowledgeSection(base: KnowledgeBase, onPickFile: () -> Unit) {
    var adding by remember { mutableStateOf<String?>(null) } // "texto" ou "link"
    var removing by remember { mutableStateOf<KnowledgeSource?>(null) }
    var instructions by remember(base.instructions) { mutableStateOf(base.instructions) }
    SectionCard("Base de conhecimento", OstieIcons.Document) {
        SettingSwitch("Usar a base de conhecimento", base.enabled, base::updateEnabled,
            "Carregue textos, links ou arquivos (PDF, MD, TXT, DOCX) de uma empresa, produto, curso ou pessoa, e o OSTIE atende com base neles no chat e no Live.")
        OutlinedTextField(value = instructions, onValueChange = { instructions = it.take(2_000) },
            label = { Text("Instruções de atendimento (opcional)") },
            placeholder = { Text("Ex.: Você é o atendente da Padaria Sol. Seja cordial, apresente o cardápio e passe o WhatsApp para pedidos.") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp), shape = MaterialTheme.shapes.medium)
        if (instructions != base.instructions)
            Button(onClick = { base.updateInstructions(instructions) }) { Text("Salvar instruções") }
        SettingSwitch("Responder só com o que está na base", base.strict, base::updateStrict,
            "Ligado: fora da base, o OSTIE diz que não sabe. Desligado: completa com conhecimento geral, avisando.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { adding = "texto" }, enabled = !base.busy) { Text("Texto") }
            OutlinedButton(onClick = { adding = "link" }, enabled = !base.busy) { Text("Link") }
            OutlinedButton(onClick = onPickFile, enabled = !base.busy) { Text("Arquivo") }
        }
        if (base.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        base.status?.let { Hint(it) }
        if (base.sources.isEmpty()) Hint("Nenhuma fonte ainda. PDF é lido pelo Gemini uma vez (precisa da chave Gemini); o resto é lido no próprio celular.")
        base.sources.forEach { source ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(source.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${source.kind.uppercase()} · ${"%,d".format(source.text.length)} caracteres",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BarIcon(OstieIcons.Delete, "Remover ${source.title}", { removing = source }, tint = MaterialTheme.colorScheme.error)
            }
        }
    }
    adding?.let { kind -> AddSourceDialog(kind, onDismiss = { adding = null }) { title, value ->
        if (kind == "link") base.addLink(value) else base.addText(title, value)
        adding = null
    } }
    removing?.let { source ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remover \"${source.title}\"?") },
            text = { Text("O OSTIE deixa de usar essa fonte nas respostas.") },
            confirmButton = { TextButton(onClick = { base.remove(source.id); removing = null }) { Text("Remover") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancelar") } })
    }
}

@Composable
private fun AddSourceDialog(kind: String, onDismiss: () -> Unit, onAdd: (title: String, value: String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    val link = kind == "link"
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (link) "Adicionar link" else "Adicionar texto") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!link) OutlinedTextField(title, { title = it.take(80) }, label = { Text("Título") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value, { value = it }, label = { Text(if (link) "Endereço (https://…)" else "Conteúdo") },
                    singleLine = link, modifier = Modifier.fillMaxWidth().heightIn(min = if (link) 56.dp else 160.dp, max = 320.dp))
                if (link) Hint("Páginas, PDFs e arquivos de texto públicos. Páginas que exigem login não funcionam.")
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(title, value) }, enabled = value.isNotBlank()) { Text("Adicionar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

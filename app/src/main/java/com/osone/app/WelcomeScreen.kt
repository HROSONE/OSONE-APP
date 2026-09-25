package com.osone.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Primeiro uso: chave Gemini (com link para criar), microfone e notificações, em três passos. */
@Composable
fun WelcomeScreen(keySaved: Boolean, keyStatus: String?, onSaveKey: (String) -> Boolean, onOpenKeyPage: () -> Unit,
    micGranted: Boolean, onMicrophone: () -> Unit, notificationsGranted: Boolean, onNotifications: () -> Unit,
    onDone: () -> Unit) {
    var key by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
        .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            OrbLogo(88.dp)
            Spacer(Modifier.height(12.dp))
            Text("Bem-vindo ao OSTIE", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("Três passos rápidos e ele já conversa, pesquisa e age no seu celular.", textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SectionCard("1. Chave Gemini", OstieIcons.Shield) {
            if (keySaved) Text("Chave salva e conferida neste aparelho.", color = OstieColors.Success)
            else {
                Hint("É grátis: entre com sua conta Google, toque em \"Create API key\", copie e cole aqui. Ela fica criptografada no celular.")
                OutlinedButton(onClick = onOpenKeyPage) { Text("Criar chave no Google AI Studio") }
                OutlinedTextField(value = key, onValueChange = { key = it.trim() }, singleLine = true,
                    placeholder = { Text("Cole a chave (AIza…)") }, visualTransformation = PasswordVisualTransformation(),
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
                Button(onClick = { if (onSaveKey(key)) key = "" }, enabled = key.isNotBlank()) { Text("Salvar chave") }
                keyStatus?.let { Hint(it) }
            }
        }
        SectionCard("2. Microfone", OstieIcons.Mic) {
            Hint("Para conversar por voz no Live e ditar mensagens.")
            if (micGranted) Text("Liberado.", color = OstieColors.Success)
            else OutlinedButton(onClick = onMicrophone) { Text("Liberar microfone") }
        }
        SectionCard("3. Notificações", OstieIcons.Chat) {
            Hint("Para lembretes, resultados das rotinas e avisos de atualização.")
            if (notificationsGranted) Text("Liberadas.", color = OstieColors.Success)
            else OutlinedButton(onClick = onNotifications) { Text("Liberar notificações") }
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(if (keySaved) "Começar" else "Pular por enquanto") }
        Hint("Depois dá para mudar tudo em Ajustes: outras chaves (Groq, OpenRouter, busca), voz, memória e mais.")
    }
}

package com.osone.app

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Testes de tela na JVM: pegam regressões como o preview que não abria na Aba de Escrita. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ScreensTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun writingScreen(workspace: WritingWorkspace) = compose.setContent {
        OstieTheme(false) {
            WritingScreen(workspace, LiveSession.get(app), CodeAuthor.get(app), AppDiagnostics.get(app),
                onDiagnostics = {}, onBack = {}, onLive = {})
        }
    }

    @Test fun svgSentByOstieOpensPreviewAutomatically() {
        val workspace = WritingWorkspace.get(app).apply { clear() }
        writingScreen(workspace)
        compose.runOnIdle {
            workspace.publish(JSONObject().put("titulo", "Desenho").put("formato", "svg")
                .put("conteudo", "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\"><circle cx=\"5\" cy=\"5\" r=\"4\"/></svg>"))
        }
        compose.onNodeWithContentDescription("Fechar visualização").assertExists()
        compose.onNodeWithContentDescription("Fechar visualização").performClick()
        compose.onNodeWithContentDescription("Visualizar HTML ou SVG").assertIsEnabled()
    }

    @Test fun typedHtmlCanBePreviewedAndPlainTextCannot() {
        val workspace = WritingWorkspace.get(app).apply { clear(); updateContent("Lista de compras") }
        writingScreen(workspace)
        compose.onNodeWithContentDescription("Visualizar HTML ou SVG").assertIsNotEnabled()
        compose.runOnIdle { workspace.updateContent("<h1>Olá</h1><p>Página de teste</p>") }
        compose.onNodeWithContentDescription("Visualizar HTML ou SVG").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Fechar visualização").assertExists()
    }

    @Test fun routinesScreenCreatesReminderAndAsksForCalendar() {
        var calendarAsked = false
        var notificationsAsked = false
        compose.setContent {
            OstieTheme(false) {
                RoutinesScreen(RoutineStore.get(app), AppDiagnostics.get(app), onDiagnostics = {}, onBack = {},
                    onRunNow = {}, onNeedNotifications = { notificationsAsked = true },
                    calendarAccess = false, onCalendar = { calendarAsked = true })
            }
        }
        compose.onNodeWithText("Permitir agenda").performClick()
        compose.onNodeWithText("Nova rotina").performClick()
        compose.onNodeWithText("Salvar").assertIsNotEnabled()
        compose.onNodeWithText("Só lembrete").assertExists()
        compose.onNodeWithText("Nome").performTextInput("Remédio de teste")
        compose.onNodeWithText("O que o OSTIE deve fazer").performTextInput("Tomar o remédio")
        compose.onNodeWithText("Horário (HH:MM)").performTextReplacement("21:15")
        compose.onNodeWithText("Salvar").assertIsEnabled().performClick()
        compose.onNodeWithText("Remédio de teste").assertExists()
        compose.runOnIdle {
            assertTrue(calendarAsked)
            assertTrue(notificationsAsked)
            val saved = RoutineStore.get(app).routines.first { it.title == "Remédio de teste" }
            assertEquals(21, saved.hour)
            assertEquals(15, saved.minute)
            RoutineStore.get(app).remove(saved.id)
        }
    }

    @Test fun routineCanBeEditedAndShowsNextRunAndExactAlarmHint() {
        val store = RoutineStore.get(app)
        val routine = store.add("Resumo de teste", "Resuma a agenda", 8, 0, emptySet(), reminder = false)
        var exactAsked = false
        compose.setContent {
            OstieTheme(false) {
                RoutinesScreen(store, AppDiagnostics.get(app), onDiagnostics = {}, onBack = {},
                    onRunNow = {}, onNeedNotifications = {}, calendarAccess = true, onCalendar = {},
                    exactAlarms = false, onExactAlarms = { exactAsked = true })
            }
        }
        compose.onNodeWithText("Liberar").performClick()
        compose.onNodeWithText("Próxima:", substring = true).assertExists()
        compose.onNodeWithText("Editar").performClick()
        compose.onNodeWithText("Editar rotina").assertExists()
        compose.onNodeWithText("Horário (HH:MM)").performTextReplacement("09:30")
        compose.onNodeWithText("Salvar").assertIsEnabled().performClick()
        compose.onNodeWithText("09:30 · todos os dias", substring = true).assertExists()
        compose.runOnIdle {
            assertTrue(exactAsked)
            val saved = store.find(routine.id)!!
            assertEquals(9, saved.hour)
            assertEquals(30, saved.minute)
            assertEquals("Resumo de teste", saved.title)
            store.remove(routine.id)
        }
    }

    @Test fun liveScreenRendersIdleAndOpensWriting() {
        // O orbe respira sem parar: com o relógio automático, o teste nunca ficaria ocioso.
        compose.mainClock.autoAdvance = false
        var writing = false
        var back = false
        compose.setContent {
            OstieTheme(false) {
                LiveScreen(LiveVoiceViewModel(app), CodeAuthor.get(app), AppDiagnostics.get(app), bubblePermission = false,
                    accessibilityEnabled = false,
                    phone = PhoneAccess(false, false, false, false, {}, {}, {}, {}),
                    onAccessibility = {}, onWriting = { writing = true }, onOverlay = {}, onShareScreen = {},
                    onStopScreen = {}, onCameraToggle = {}, onCameraSwitch = {}, onEnd = {}, onDiagnostics = {},
                    onBack = { back = true })
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Pronto para conversar").assertExists()
        compose.onNodeWithContentDescription("Aba de Escrita").performClick()
        compose.onNodeWithContentDescription("Voltar ao chat").performClick()
        compose.mainClock.advanceTimeByFrame()
        assertTrue(writing)
        assertTrue(back)
    }

    @Test fun settingsShowChatActionsForEveryProvider() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val chat = OsoneViewModel(app)
        compose.setContent {
            OstieTheme(false) {
                SettingsScreen(chat, LiveVoiceViewModel(app), CodeAuthor.get(app), AppUpdater(activity), MemoryStore.get(app),
                    darkMode = false, onMemoryFolder = {}, onDarkMode = {}, onBack = {}, onPickUpdate = {},
                    diagnostics = AppDiagnostics.get(app), onDiagnostics = {})
            }
        }
        compose.onNodeWithText("Ações no chat escrito").performScrollTo().assertExists()
        compose.onNodeWithText("No Groq e no OpenRouter", substring = true).assertExists()
        compose.runOnIdle { chat.updateChatTools(false) }
        compose.runOnIdle { assertEquals(false, app.getSharedPreferences("osone_config", 0).getBoolean("chat_tools", true)) }
        compose.runOnIdle { chat.updateChatTools(true) }
    }

    @Test fun codeAuthorDialogReportsTheChosenModel() {
        var choice: Boolean? = null
        compose.setContent {
            OstieTheme(false) {
                CodeAuthorDialog(CodeRequest(null, "Jogo", "Um jogo da velha em HTML", "html", false) {},
                    voiceLabel = null, textLabel = "Gemini 3.8 Flash",
                    onChoose = { useText, _ -> choice = useText }, onDismiss = {})
            }
        }
        compose.onNodeWithText("Qual modelo deve codar?").assertExists()
        // Sem Live conectado, o modelo de voz não pode ser escolhido.
        compose.onNodeWithText("Modelo de voz").performClick()
        compose.runOnIdle { assertEquals(null, choice) }
        compose.onNodeWithText("Modelo de texto").performClick()
        compose.runOnIdle { assertEquals(true, choice) }
    }
}

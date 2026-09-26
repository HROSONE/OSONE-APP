package com.osone.app

import java.text.Normalizer

/**
 * Guias de editores de vídeo para o agente: onde costumam ficar importar, cortar, texto, transição, música e exportar.
 * As telas mudam entre versões, então cada guia diz "costuma" e manda conferir com look_at_screen.
 * Entra sozinho no resultado de look_at_screen, inspect_screen e open_app quando o app aberto é um editor
 * (uma vez a cada [REPEAT_MS] por app) e também pela ferramenta editor_guide. Lógica pura, testável na JVM.
 */
object EditorGuides {
    const val TOOL = "editor_guide"
    const val REPEAT_MS = 15 * 60_000L

    class Guide(val name: String, val packages: List<String>, val aliases: List<String>, val steps: List<String>)

    /** Vale para todos os editores com linha do tempo. */
    val COMMON = listOf(
        "Linha do tempo: a agulha (linha vertical) marca o ponto de edição; arraste a linha do tempo na horizontal (não a agulha) para mover o ponto. Pinça (ampliar/reduzir) na linha do tempo muda o zoom: amplie antes de cortes precisos.",
        "Selecionar um clipe: toque nele na linha do tempo; aparece uma borda com alças nas pontas e a barra de ferramentas muda.",
        "Aparar: com o clipe selecionado, arraste a alça do início ou do fim (screen_gesture arrastar com duracao_ms 1000 a 2000).",
        "Mover clipe: segure o clipe e arraste (tap_mark acao=arrastar ou screen_gesture arrastar).",
        "Depois de cada edição importante, confira com look_at_screen; se a barra de ferramentas mudou, procure o botão pelo nome antes de tocar.",
        "Exportar pode demorar: depois de tocar em exportar, use wait_for_ui com o texto de conclusão (ex.: Salvo, Concluído, Compartilhar) e segundos 15, repetindo se precisar. Não feche o app durante a exportação.",
        "Nunca apague projetos nem publique em redes sociais sem o sim do usuário.")

    val GUIDES = listOf(
        Guide("CapCut", listOf("com.lemon.lvoverseas", "com.ss.android.ugc.cc"), listOf("capcut"), listOf(
            "Começar: na tela inicial, \"Novo projeto\" (+); marque os vídeos e fotos na galeria e toque em \"Adicionar\".",
            "Ferramentas ficam na barra inferior; com um clipe selecionado aparecem: Dividir, Velocidade, Volume, Animação, Excluir, Editar (cortar/girar), Filtros, Ajustar.",
            "Cortar em duas partes: posicione a agulha e toque em \"Dividir\".",
            "Texto: sem nada selecionado, \"Texto\" > \"Adicionar texto\", digite e confirme (✓); arraste a faixa de texto para ajustar o tempo.",
            "Transição: toque no pequeno quadrado branco entre dois clipes na linha do tempo e escolha o efeito.",
            "Música: \"Áudio\" > \"Sons\" (ou \"Extraído\" para o áudio de um vídeo).",
            "Legendas automáticas: \"Texto\" > \"Legendas automáticas\".",
            "Exportar: botão no canto superior direito (seta para cima / \"Exportar\"); a resolução fica ao lado (ex.: 1080P)."
        )),
        Guide("VN", listOf("com.frontrow.vlog"), listOf("vn", "vn video editor", "vlognow"), listOf(
            "Começar: botão \"+\" > \"Novo projeto\"; escolha os clipes e toque na seta/confirmar.",
            "Com o clipe selecionado, a barra inferior mostra: Dividir, Velocidade, Volume, Cortar, Filtro, Excluir.",
            "Cortar em duas partes: agulha no ponto e \"Dividir\".",
            "Texto: \"Texto\" na barra principal; música: \"Música\" (ou o ícone de nota musical na linha do tempo).",
            "Transição: ícone entre os clipes na linha do tempo.",
            "Exportar: botão no canto superior direito; escolha resolução e taxa de quadros e confirme."
        )),
        Guide("InShot", listOf("com.camerasideas.instashot"), listOf("inshot"), listOf(
            "Começar: \"Vídeo\" > \"Novo\"; marque os clipes e toque em ✓.",
            "Barra inferior: Tela (proporção), Música, Texto, Figurinhas, Aparar, Dividir, Velocidade, Filtro, Excluir.",
            "Aparar: \"Aparar\" e arraste as alças; \"Dividir\" corta na agulha.",
            "Transição: ícone entre os clipes.",
            "Exportar: \"Salvar\" no canto superior direito; escolha a resolução."
        )),
        Guide("KineMaster", listOf("com.nexstreaming.app.kinemasterfree", "com.kinemaster.app"), listOf("kinemaster"), listOf(
            "Começar: \"Criar\" (+) e escolha a proporção; adicione mídia pelo navegador de mídia.",
            "Roda de ferramentas à direita: Mídia, Camada (texto, efeito, adesivo), Áudio, Gravar voz.",
            "Com o clipe selecionado, o painel à direita mostra as opções; o ícone de tesoura corta (\"Dividir na agulha\").",
            "Transição: ícone \"+\" entre clipes.",
            "Exportar: ícone de exportar/compartilhar no canto superior direito; escolha resolução e \"Salvar como vídeo\"."
        )),
        Guide("PowerDirector", listOf("com.cyberlink.powerdirector.DRA140225_01"), listOf("powerdirector"), listOf(
            "Começar: \"Novo projeto\", dê um nome e escolha a proporção; adicione clipes com \"+\".",
            "Com o clipe selecionado: Editar, Dividir, Velocidade, Excluir, Volume.",
            "Texto: \"Camadas\" > \"Texto\"; transição: ícone entre clipes.",
            "Exportar: ícone de produzir/compartilhar no canto superior direito."
        )),
        Guide("Filmora", listOf("com.wondershare.filmorago"), listOf("filmora", "filmorago"), listOf(
            "Começar: \"Novo projeto\"; importe os clipes.",
            "Barra inferior com o clipe selecionado: Aparar, Dividir, Velocidade, Excluir; sem seleção: Texto, Música, Efeitos, Filtro.",
            "Transição: ícone entre clipes.",
            "Exportar: botão \"Exportar\" no canto superior direito."
        )),
        Guide("LumaFusion", listOf("com.luma_touch.lumafusion"), listOf("lumafusion", "luma fusion"), listOf(
            "Editor profissional com várias trilhas: a biblioteca de mídia fica em cima à esquerda, o visualizador à direita e a linha do tempo embaixo.",
            "Arraste o clipe da biblioteca para a linha do tempo (screen_gesture arrastar com duracao_ms 1500).",
            "Com o clipe selecionado, a barra de ferramentas embaixo tem Dividir (tesoura) e Excluir (lixeira); toque duplo no clipe abre o editor de clipe (cor, efeitos, velocidade, áudio).",
            "Títulos: botão \"+\" > Título; transição: toque entre dois clipes.",
            "Exportar: ícone de compartilhar/exportar no canto superior direito."
        )),
        Guide("Adobe Premiere", listOf("com.adobe.premiererush.videoeditor", "com.adobe.premiere"), listOf("premiere", "premiere rush", "rush"), listOf(
            "Começar: \"Criar novo vídeo\"/\"+\"; escolha as mídias e confirme.",
            "Com o clipe selecionado: Dividir (tesoura), Duplicar, Excluir; ferramentas de Texto, Áudio, Cor e Velocidade na barra inferior.",
            "Exportar: botão de compartilhar/exportar no canto superior direito."
        )))

    private fun normalized(value: String) = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").trim()

    /** Guia pelo pacote do app aberto ou pelo nome dito pelo usuário. */
    fun find(appOrPackage: String): Guide? {
        val key = normalized(appOrPackage)
        if (key.isEmpty()) return null
        return GUIDES.firstOrNull { guide -> guide.packages.any { it.lowercase() == key } }
            ?: GUIDES.firstOrNull { guide -> normalized(guide.name) == key || guide.aliases.any { it == key } }
            ?: GUIDES.firstOrNull { guide -> (listOf(normalized(guide.name)) + guide.aliases).any { it.length > 3 && key.contains(it) } }
    }

    fun text(guide: Guide): String = "Guia do ${guide.name} (as telas mudam entre versões; confira com look_at_screen): " +
        guide.steps.joinToString(" ") + " Em qualquer editor: " + COMMON.joinToString(" ")

    /** Resposta da ferramenta editor_guide; sem guia próprio, devolve o geral. */
    fun answer(app: String): String = find(app)?.let(::text)
        ?: ("Sem guia específico para \"${app.take(40)}\". Guias prontos: ${GUIDES.joinToString { it.name }}. " +
            "Regras gerais: " + COMMON.joinToString(" "))

    /** Anexa o guia uma vez por app a cada [REPEAT_MS], para não encher a conversa. */
    class Tracker(private val clock: () -> Long = System::currentTimeMillis) {
        private val sent = HashMap<String, Long>()

        @Synchronized fun guideFor(packageName: String): String? {
            val guide = find(packageName)?.takeIf { g -> g.packages.any { it.equals(packageName, true) } } ?: return null
            val now = clock()
            if (now - (sent[guide.name] ?: Long.MIN_VALUE / 2) < REPEAT_MS) return null
            sent[guide.name] = now
            return text(guide)
        }
    }
}

package com.osone.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Cópia de segurança dos ajustes e rotinas (nunca das chaves, que ficam no cofre do aparelho).
 * Formato: {"versao":1, "ajustes":{...}, "rotinas":[...]}. Lógica pura, testável na JVM.
 */
object SettingsBackup {
    const val FILE = "backup-ajustes.json"
    /** Nada que pareça chave ou segredo sai do aparelho, mesmo que um dia vá parar nas preferências. */
    private val secret = Regex("(^|_)(key|token|secret|senha|password)($|_)", RegexOption.IGNORE_CASE)

    fun build(settings: Map<String, *>, routines: JSONArray, now: Long): String {
        val values = JSONObject()
        settings.toSortedMap().forEach { (name, value) ->
            if (secret.containsMatchIn(name)) return@forEach
            when (value) {
                is Boolean, is Int, is Long, is Float, is String -> values.put(name, value)
            }
        }
        return JSONObject().put("versao", 1).put("criado_em", now).put("ajustes", values).put("rotinas", routines).toString(2)
    }

    class Parsed(val settings: Map<String, Any>, val routines: JSONArray)

    /** Número do JSON no tipo que a preferência já usa (Int, Long ou Float); sem tipo conhecido, Int se couber. */
    fun number(value: Number, current: Any?): Any = when (current) {
        is Int -> value.toInt()
        is Long -> value.toLong()
        is Float -> value.toFloat()
        else -> if (value.toDouble() % 1.0 == 0.0) {
            if (value.toLong() in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else value.toLong()
        } else value.toFloat()
    }

    /**
     * O valor da cópia pode ir para a preferência sem trocar o tipo dela? Tipo trocado faz o app fechar ao ler
     * (ex.: texto onde o app espera sim/não). Preferência que ainda não existe aceita qualquer tipo.
     */
    fun compatible(value: Any, current: Any?): Boolean = when (current) {
        null -> value is Boolean || value is String || value is Number
        is Boolean -> value is Boolean
        is String -> value is String
        is Int, is Long, is Float -> value is Number
        else -> false
    }

    fun parse(text: String): Parsed {
        val json = JSONObject(text)
        require(json.optInt("versao") == 1) { "Arquivo de cópia desconhecido." }
        val values = json.optJSONObject("ajustes") ?: JSONObject()
        val settings = LinkedHashMap<String, Any>()
        for (name in values.keys()) {
            if (secret.containsMatchIn(name)) continue
            when (val value = values.get(name)) {
                is Boolean, is String, is Number -> settings[name] = value
            }
        }
        return Parsed(settings, json.optJSONArray("rotinas") ?: JSONArray())
    }
}

package io.github.obdlivre.core

/**
 * Leitor de JSON mínimo, sem dependências (o par de [Json]).
 * Devolve Map<String, Any?>, List<Any?>, String, Double, Boolean ou null.
 */
object MiniJson {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != text.length) p.fail("conteúdo extra")
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun fail(msg: String): Nothing = throw IllegalArgumentException("JSON inválido em $i: $msg")

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            if (i >= s.length) fail("fim inesperado")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else fail("caractere '$c'")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("esperado $word")
            i += word.length
            return v
        }

        fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDoubleOrNull() ?: fail("número")
        }

        fun str(): String {
            i++ // abre aspas
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("texto sem fim")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = s[i++]
                        when (e) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> fail("escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun arr(): List<Any?> {
            i++
            val out = mutableListOf<Any?>()
            ws()
            if (s[i] == ']') { i++; return out }
            while (true) {
                ws(); out.add(value()); ws()
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> fail("esperado , ou ]")
                }
            }
        }

        fun obj(): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (s[i] == '}') { i++; return out }
            while (true) {
                ws()
                if (s[i] != '"') fail("esperado nome")
                val k = str()
                ws()
                if (s[i++] != ':') fail("esperado :")
                ws()
                out[k] = value()
                ws()
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> fail("esperado , ou }")
                }
            }
        }
    }
}

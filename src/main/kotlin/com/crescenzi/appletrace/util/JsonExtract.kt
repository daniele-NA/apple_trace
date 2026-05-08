package com.crescenzi.appletrace.util

/**
 * Tiny, allocation-free extractor for shallow JSON string/number values produced
 * by Apple's `log stream --style ndjson`. Not a general-purpose JSON parser.
 *
 * It walks the input looking for `"key":` and reads the next primitive token,
 * unescaping a small set of common string escapes. Good enough for the fixed
 * shape of unified-log records, where the keys we care about (eventMessage,
 * subsystem, category, processImagePath, messageType, timestamp) appear at the
 * top level.
 */
internal object JsonExtract {

    fun string(json: String, key: String): String? {
        val start = findValueStart(json, key) ?: return null
        if (start >= json.length) return null
        return when (json[start]) {
            '"' -> readString(json, start + 1)
            'n' -> null // null
            else -> null
        }
    }

    fun int(json: String, key: String): Int? {
        val start = findValueStart(json, key) ?: return null
        var i = start
        val sb = StringBuilder()
        if (i < json.length && (json[i] == '-' || json[i].isDigit())) {
            sb.append(json[i]); i++
            while (i < json.length && json[i].isDigit()) {
                sb.append(json[i]); i++
            }
            return sb.toString().toIntOrNull()
        }
        return null
    }

    private fun findValueStart(json: String, key: String): Int? {
        val needle = "\"$key\""
        var idx = 0
        while (true) {
            val k = json.indexOf(needle, idx)
            if (k < 0) return null
            // Ensure this is a key, not a value: the next non-space char must be ':'.
            var j = k + needle.length
            while (j < json.length && json[j].isWhitespace()) j++
            if (j < json.length && json[j] == ':') {
                j++
                while (j < json.length && json[j].isWhitespace()) j++
                return j
            }
            idx = k + needle.length
        }
    }

    private fun readString(json: String, from: Int): String {
        val sb = StringBuilder(json.length - from)
        var i = from
        while (i < json.length) {
            val c = json[i]
            if (c == '"') return sb.toString()
            if (c == '\\' && i + 1 < json.length) {
                when (val esc = json[i + 1]) {
                    '"', '\\', '/' -> sb.append(esc)
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('')
                    'u' -> if (i + 5 < json.length) {
                        val hex = json.substring(i + 2, i + 6)
                        val code = hex.toIntOrNull(16)
                        if (code != null) sb.append(code.toChar())
                        i += 4
                    }
                    else -> sb.append(esc)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}

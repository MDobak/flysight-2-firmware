package com.example.flysightstatus

/**
 * A FlySight config file (CONFIG.TXT or a profile in /CONFIG).
 *
 * The model keeps all lines, comments, and unknown keys. A change replaces
 * only the value in its line, so the file keeps its layout and comments.
 * It reads lines as the firmware does: the text after ";" is a comment, and
 * the first two words, separated by space, tab, or ":", are the key and the value.
 */
class ConfigFile(text: String) {

    private class Line(var raw: String) {
        var key: String? = null
        var value: String? = null
        var valueStart = 0
        var valueEnd = 0

        init {
            parse()
        }

        fun parse() {
            key = null
            value = null
            val code = raw.substringBefore(';')
            val tokens = TOKEN.findAll(code).take(2).toList()
            if (tokens.size < 2) return
            key = tokens[0].value
            value = tokens[1].value
            valueStart = tokens[1].range.first
            valueEnd = tokens[1].range.last + 1
        }

        fun replaceValue(newValue: String) {
            if (key == null) return
            raw = raw.substring(0, valueStart) + newValue + raw.substring(valueEnd)
            parse()
        }

        /** True for a comment line that continues the description of the key above it. */
        val isContinuation get() = key == null && raw.isNotEmpty() && raw[0].isWhitespace() && raw.trim().startsWith(";")
    }

    private val lines: MutableList<Line>
    private val newline: String = if (text.contains("\r\n")) "\r\n" else "\n"

    init {
        val parts = text.split("\r\n", "\n").toMutableList()
        if (parts.isNotEmpty() && parts.last().isEmpty()) parts.removeAt(parts.size - 1)
        lines = parts.map { Line(it) }.toMutableList()
    }

    fun text(): String = lines.joinToString(newline) { it.raw } + newline

    fun has(key: String) = lines.any { it.key == key }

    /** The value that the firmware uses: the last line with the key. */
    fun get(key: String): String? = lines.lastOrNull { it.key == key }?.value

    /** The index of the last line with the key, or -1. The firmware uses the value of the last line. */
    fun lastLine(key: String): Int = lines.indexOfLast { it.key == key }

    /** The firmware reads numbers with atol(): the leading digits, or 0. */
    fun getInt(key: String): Int? = get(key)?.let { parseInt(it) }

    /** Set the value in the last line with the key, or add a line at the end. */
    fun set(key: String, value: String) {
        val line = lines.lastOrNull { it.key == key }
        if (line != null) {
            line.replaceValue(value)
        } else {
            lines += Line("$key:".padEnd(10) + " " + value)
        }
    }

    fun remove(key: String) {
        removeKeys(setOf(key))
    }

    /** All keys in the file in the order of the lines. */
    fun keys(): List<String> = lines.mapNotNull { it.key }.distinct()

    /**
     * Items of a list, for example the alarms. Each item starts at a line
     * with the first key of [keys] and contains the other keys after it.
     */
    fun group(keys: List<String>): List<Map<String, String>> {
        val items = mutableListOf<MutableMap<String, String>>()
        for (line in lines) {
            val key = line.key ?: continue
            val value = line.value ?: continue
            if (key == keys[0]) items += mutableMapOf(key to value)
            else if (key in keys) items.lastOrNull()?.put(key, value)
        }
        return items
    }

    /**
     * Replace a list. The new lines go where the first old line was, or at the
     * end. The indented comment lines under the old lines are also removed.
     */
    fun setGroup(keys: List<String>, items: List<Map<String, String>>) {
        if (items == group(keys)) return
        val at = removeKeys(keys.toSet())
        val new = mutableListOf<Line>()
        for (item in items) {
            for (key in keys) {
                val value = item[key] ?: continue
                new += Line("$key:".padEnd(14) + " " + value)
            }
        }
        lines.addAll(if (at >= 0) at else lines.size, new)
    }

    /** Remove the lines of [keys] and their comment lines. Returns the index of the first removed line, or -1. */
    private fun removeKeys(keys: Set<String>): Int {
        var first = -1
        var i = 0
        var removing = false
        while (i < lines.size) {
            val line = lines[i]
            if (line.key in keys || (removing && line.isContinuation)) {
                if (first < 0) first = i
                lines.removeAt(i)
                removing = true
            } else {
                removing = false
                i++
            }
        }
        return first
    }

    companion object {
        private val TOKEN = Regex("""[^\s:]+""")

        fun parseInt(value: String): Int? = Regex("""^[+-]?\d+""").find(value)?.value?.toLongOrNull()
            ?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt()
    }
}

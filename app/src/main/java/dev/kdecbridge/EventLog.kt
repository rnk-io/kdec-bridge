package dev.kdecbridge

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Timestamped event log, stored in a file so that it survives the process
 * being killed and can show what stopped the service.
 */
object EventLog {

    private const val MAX_LINES = 400
    private val clock = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    @Volatile private var file: File? = null

    data class Entry(val at: Long, val msg: String) {
        fun stamp(): String = clock.format(Date(at))
    }

    fun init(ctx: Context) {
        if (file == null) file = File(ctx.applicationContext.filesDir, "events.log")
    }

    @Synchronized
    fun add(msg: String) {
        val f = file ?: return
        runCatching {
            f.appendText(System.currentTimeMillis().toString() + "\t" + msg + "\n")
            if (f.length() > 96 * 1024) trim(f)
        }
    }

    private fun trim(f: File) {
        val keep = f.readLines().takeLast(MAX_LINES)
        f.writeText(keep.joinToString("\n", postfix = "\n"))
    }

    @Synchronized
    fun recent(limit: Int = 150): List<Entry> {
        val f = file ?: return emptyList()
        if (!f.exists()) return emptyList()
        return runCatching {
            f.readLines().takeLast(limit).mapNotNull { line ->
                val i = line.indexOf('\t')
                if (i <= 0) return@mapNotNull null
                val at = line.substring(0, i).toLongOrNull() ?: return@mapNotNull null
                Entry(at, line.substring(i + 1))
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun clear() {
        runCatching { file?.writeText("") }
    }

    /** "3m 20s ago" / "just now" */
    fun ago(millis: Long): String {
        if (millis <= 0) return "never"
        val s = (System.currentTimeMillis() - millis) / 1000
        return when {
            s < 5 -> "just now"
            s < 60 -> s.toString() + "s ago"
            s < 3600 -> (s / 60).toString() + "m " + (s % 60) + "s ago"
            s < 86400 -> (s / 3600).toString() + "h " + ((s % 3600) / 60) + "m ago"
            else -> (s / 86400).toString() + "d ago"
        }
    }

    fun clockOf(millis: Long): String =
        if (millis <= 0) "-" else clock.format(Date(millis))
}

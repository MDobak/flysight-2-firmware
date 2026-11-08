package com.example.flysightstatus

import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Finds the sessions on the FlySight and downloads their TRACK.CSV files.
 * A session folder is /YY-MM-DD/HH-MM-SS. Sessions with no valid time
 * (/TEMP/NNNN) are found only by [findAll]. All calls are on the main thread.
 */
class JumpSync(private val main: MainActivity) {

    /** A session folder on the FlySight. */
    class Session(val path: String) {
        val date: String? = SESSION.matchEntire(path)?.groupValues?.get(1)
        val time: String? = SESSION.matchEntire(path)?.groupValues?.get(2)?.replace('-', ':')?.substring(0, 5)
        val trackPath get() = join(path, "TRACK.CSV")
        val key: String get() = TrackStore.localName(trackPath).removeSuffix(".csv")
        fun localFile(main: MainActivity) = TrackStore.file(main, trackPath)
    }

    /** Progress of the current download. */
    class Progress(val index: Int, val count: Int, val done: Long, val size: Long)

    var running = false
        private set
    var progress: Progress? = null
        private set
    /** All sessions on the FlySight from the last [findAll], or null if not known. */
    var known: List<Session>? = null
        private set
    /** True while [findAll] runs. */
    var finding = false
        private set
    /** Text of the current step, for example "Reading /26-11-07…". */
    var status: String = ""
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var cancelled = false

    private fun changed() = main.onStateChanged()

    fun cancel() {
        if (!running) return
        cancelled = true
        main.files?.cancel()
    }

    /** All sessions, the newest first. [found] is called after each folder. */
    fun findAll(found: (List<Session>) -> Unit, done: (Result<List<Session>>) -> Unit) {
        if (running) return done(Result.failure(IllegalStateException("Wait for the current file operation")))
        finding = true
        find(after = null, newestDayOnly = false, found = { list ->
            known = list
            found(list)
        }) { result ->
            finding = false
            result.onSuccess { known = it }
            done(result)
            changed()
        }
    }

    /** Clear the list of known sessions, for example after a disconnect. */
    fun forget() {
        known = null
    }

    /**
     * Download the sessions that are newer than the newest session on the
     * phone. If the phone has no sessions, download the sessions of the
     * newest day on the FlySight.
     */
    fun getNew(done: (Result<List<File>>) -> Unit) {
        val newest = TrackStore.newestSession(main)
        find(after = newest, newestDayOnly = newest == null, found = {}) { result ->
            result.onSuccess { list ->
                val todo = list.filter { !it.localFile(main).exists() }.reversed()
                if (todo.isEmpty()) {
                    finish()
                    done(Result.success(emptyList()))
                } else {
                    download(todo, done)
                }
            }.onFailure { done(Result.failure(it)) }
        }
    }

    private fun find(
        after: String?,
        newestDayOnly: Boolean,
        found: (List<Session>) -> Unit,
        done: (Result<List<Session>>) -> Unit,
    ) {
        val f = main.files ?: return done(Result.failure(IllegalStateException("Not connected")))
        if (f.busy || running) return done(Result.failure(IllegalStateException("Wait for the current file operation")))
        running = true
        cancelled = false
        progress = null
        step("Reading the FlySight…")
        val afterDate = after?.substring(0, 8)
        f.list("/") { result ->
            val root = result.getOrElse {
                finish()
                return@list done(Result.failure(it))
            }
            val folders = ArrayDeque<String>()
            var days = root.filter { it.isDirectory && DATE_FOLDER.matches(it.name) }
                .map { it.name }
                .filter { afterDate == null || it >= afterDate }
                .sortedDescending()
            if (newestDayOnly) days = days.take(1)
            days.forEach { folders += "/$it" }
            if (after == null && !newestDayOnly) {
                root.firstOrNull { it.isDirectory && it.name.equals("TEMP", ignoreCase = true) }?.let { folders += "/" + it.name }
            }
            val sessions = mutableListOf<Session>()

            fun next() {
                val dir = folders.removeFirstOrNull()
                if (dir == null || cancelled) {
                    finish()
                    return if (cancelled) done(Result.failure(IllegalStateException("Cancelled"))) else done(Result.success(sessions))
                }
                step("Reading $dir…")
                val ft = main.files ?: return done(Result.failure(IllegalStateException("Disconnected"))).also { finish() }
                ft.list(dir) { r ->
                    r.onSuccess { entries ->
                        entries.filter { it.isDirectory && !it.name.startsWith(".") }
                            .sortedByDescending { it.name }
                            .map { Session(join(dir, it.name)) }
                            .filter { after == null || it.key > after }
                            .forEach { sessions += it }
                        found(sessions.toList())
                        next()
                    }.onFailure {
                        if (it.message == "Cancelled" || main.files == null) {
                            finish()
                            done(Result.failure(it))
                        } else {
                            main.log("Jumps: cannot list $dir: ${it.message}")
                            next()
                        }
                    }
                }
            }
            next()
        }
    }

    /** Download the sessions in the list order. A session with no usable track is skipped. */
    fun download(sessions: List<Session>, done: (Result<List<File>>) -> Unit) {
        val f = main.files ?: return done(Result.failure(IllegalStateException("Not connected")))
        if (f.busy) return done(Result.failure(IllegalStateException("Wait for the current file operation")))
        running = true
        cancelled = false
        val saved = mutableListOf<File>()
        var index = 0

        fun next() {
            if (cancelled || index >= sessions.size) {
                finish()
                return if (cancelled && saved.isEmpty()) done(Result.failure(IllegalStateException("Cancelled")))
                else done(Result.success(saved))
            }
            val session = sessions[index]
            val ft = main.files ?: return done(Result.failure(IllegalStateException("Disconnected"))).also { finish() }
            progress = Progress(index, sessions.size, 0, 0)
            step("Reading ${session.path}…")
            // List the folder to get the file size for the progress bar.
            ft.list(session.path) { result ->
                val info = result.getOrNull()?.firstOrNull { !it.isDirectory && it.name.equals("TRACK.CSV", ignoreCase = true) }
                if (info == null) {
                    if (result.exceptionOrNull()?.message == "Cancelled") cancelled = true
                    main.log("Jumps: ${session.path} has no TRACK.CSV")
                    index++
                    return@list next()
                }
                val path = join(session.path, info.name)
                val started = System.currentTimeMillis()
                val reader = main.files ?: return@list next()
                reader.read(path, progress = { n ->
                    progress = Progress(index, sessions.size, n.toLong(), info.size)
                    changed()
                }) { r ->
                    r.onSuccess { data ->
                        main.log("Jumps: read $path, ${data.size} bytes in ${(System.currentTimeMillis() - started) / 1000.0} s")
                        step("Saving ${session.path}…")
                        Thread {
                            val file = runCatching {
                                val track = Track.parse(session.key, data.toString(Charsets.ISO_8859_1))
                                TrackStore.saveParsed(main, TrackStore.localName(path), data, track)
                            }
                            handler.post {
                                file.onSuccess { saved += it }
                                    .onFailure { main.log("Jumps: ${session.path} skipped: ${it.message}") }
                                index++
                                next()
                            }
                        }.start()
                    }.onFailure {
                        if (it.message == "Cancelled" || main.files == null) {
                            cancelled = true
                        } else {
                            main.log("Jumps: cannot read $path: ${it.message}")
                            index++
                        }
                        next()
                    }
                }
            }
        }
        next()
    }

    private fun step(text: String) {
        status = text
        changed()
    }

    private fun finish() {
        running = false
        progress = null
        status = ""
        changed()
    }

    companion object {
        private val DATE_FOLDER = Regex("""\d\d-\d\d-\d\d""")
        private val SESSION = Regex("""/(\d\d-\d\d-\d\d)/(\d\d-\d\d-\d\d)""")

        fun join(dir: String, name: String) = if (dir.endsWith("/")) dir + name else "$dir/$name"
    }
}

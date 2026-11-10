package com.mdobak.flysightstatus

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.IOException
import com.mdobak.flysightstatus.FlySightProtocol as P

/**
 * File operations over the File_Transfer service (crs.c in the firmware).
 *
 * Commands go to FT_Packet_In (write without response). Answers come as
 * notifications on FT_Packet_Out, which the caller must enable and pass to
 * [onPacketOut]. File data uses Go-Back-N with a window of 8 packets.
 * Only one operation can run at a time. All calls must be on the main thread.
 *
 * The firmware can lose a command:
 * - In the read state, it discards all packets other than FILE_ACK and CANCEL.
 *   If the last ACK of a read and the next command arrive together, it
 *   discards the command.
 * - After a CANCEL, a command in its queue waits until the next packet comes.
 * Thus, after a read or a failure, the app waits [SETTLE_MS] before the next
 * command. If a list or read command gets no ACK, the app sends CANCEL and
 * sends the command one more time.
 */
class FileTransfer(
    private val connection: FlySightConnection,
    private val log: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var op: Op<*>? = null

    /** The time (uptime ms) before which the app must not send a command. */
    private var quietUntil = 0L

    val busy: Boolean get() = op != null

    fun list(path: String, done: (Result<List<FileInfo>>) -> Unit) =
        start(ListOp(done).apply { commandRetries = 1 }, byteArrayOf(P.FT_LIST.toByte()) + path.encodePath())

    fun read(path: String, progress: (Int) -> Unit, done: (Result<ByteArray>) -> Unit) {
        // [0x02] [offset multiplier u32] [stride-1 multiplier u32] [path]
        val header = byteArrayOf(P.FT_READ.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
        start(ReadOp(progress, done).apply { commandRetries = 1 }, header + path.encodePath())
    }

    fun write(path: String, data: ByteArray, progress: (Int, Int) -> Unit, done: (Result<Unit>) -> Unit) =
        start(WriteOp(data, progress, done), byteArrayOf(P.FT_WRITE.toByte()) + path.encodePath())

    fun delete(path: String, done: (Result<Unit>) -> Unit) =
        start(SimpleOp(P.FT_DELETE, done), byteArrayOf(P.FT_DELETE.toByte()) + path.encodePath())

    fun mkdir(path: String, done: (Result<Unit>) -> Unit) =
        start(SimpleOp(P.FT_MKDIR, done), byteArrayOf(P.FT_MKDIR.toByte()) + path.encodePath())

    /** Stop the current operation on the FlySight and in the app. */
    fun cancel() {
        val current = op ?: return
        send(byteArrayOf(P.FT_CANCEL.toByte()))
        quiet()
        current.fail("Cancelled")
    }

    /** Call after a disconnect. */
    fun reset() {
        op?.fail("Disconnected")
    }

    fun ping() = send(byteArrayOf(P.FT_PING.toByte()))

    /** Handle one notification from FT_Packet_Out. */
    fun onPacketOut(value: ByteArray) {
        if (value.isEmpty()) return
        val type = value[0].toInt() and 0xff
        val arg = if (value.size > 1) value[1].toInt() and 0xff else -1
        val current = op
        when (type) {
            P.FT_ACK -> when {
                arg == P.FT_PING -> Unit
                current != null && arg == current.command && !current.acknowledged -> {
                    current.acknowledged = true
                    current.onAck()
                }
                else -> log(String.format("FT: unexpected ACK 0x%02X", arg))
            }
            P.FT_NAK -> if (current != null) current.onNak(arg)
            else log(String.format("FT: NAK 0x%02X", arg))
            P.FT_FILE_DATA -> if (current is ReadOp && current.acknowledged) current.onData(value)
            // Data of an earlier read: a lost ACK makes the FlySight send packets again.
            // Acknowledge them, or it never stops.
            else if (arg >= 0) send(byteArrayOf(P.FT_FILE_ACK.toByte(), arg.toByte()))
            P.FT_FILE_INFO -> if (current is ListOp && current.acknowledged) current.onInfo(value)
            P.FT_FILE_ACK -> if (current is WriteOp) current.onFileAck(arg)
            else -> log(String.format("FT: unknown packet 0x%02X", type))
        }
    }

    private fun start(newOp: Op<*>, command: ByteArray) {
        if (op != null) {
            newOp.finishWith(IOException("Another file operation is in progress"))
            return
        }
        op = newOp
        newOp.commandPacket = command
        val wait = quietUntil - SystemClock.uptimeMillis()
        if (wait > 0) handler.postDelayed({ if (op === newOp) newOp.sendCommand() }, wait)
        else newOp.sendCommand()
    }

    /** Do not send a command for [SETTLE_MS], so that the FlySight can go back to idle. */
    private fun quiet() {
        quietUntil = SystemClock.uptimeMillis() + SETTLE_MS
    }

    private fun send(packet: ByteArray) =
        connection.write(P.FILE_TRANSFER_SERVICE, P.FT_PACKET_IN, packet, withResponse = false)

    // The FlySight firmware does not end the path of the create, delete and
    // mkdir commands. Old bytes of its buffer then follow the path. Thus the
    // app sends a zero byte after the path.
    private fun String.encodePath(): ByteArray = toByteArray(Charsets.ISO_8859_1) + 0.toByte()

    // ---- Operations ----

    private abstract inner class Op<T>(val command: Int, private val done: (Result<T>) -> Unit) {
        var acknowledged = false
        lateinit var commandPacket: ByteArray

        /** How many times the app can send the command again if it gets no ACK. Only for commands with no side effects. */
        var commandRetries = 0
        private val timer = Runnable { onTimeout() }

        fun sendCommand() {
            send(commandPacket)
            armTimer(if (commandRetries > 0) RETRY_COMMAND_TIMEOUT_MS else COMMAND_TIMEOUT_MS)
        }

        fun armTimer(ms: Long) {
            handler.removeCallbacks(timer)
            handler.postDelayed(timer, ms)
        }

        fun finish(value: T) = complete(Result.success(value))

        fun finishWith(error: Throwable) = complete(Result.failure(error))

        fun fail(message: String) = finishWith(IOException(message))

        private fun complete(result: Result<T>) {
            handler.removeCallbacks(timer)
            if (op === this) op = null
            if (result.isFailure || this is ReadOp) quiet()
            done(result)
        }

        abstract fun onAck()

        open fun onNak(command: Int) {
            if (command == this.command) {
                fail("The FlySight refused the command. File access works only in idle mode, and the path must exist.")
            } else {
                log(String.format("FT: NAK 0x%02X", command))
            }
        }

        open fun onTimeout() {
            send(byteArrayOf(P.FT_CANCEL.toByte()))
            if (!acknowledged && commandRetries > 0) {
                // The FlySight lost the command, or it was busy with an earlier operation
                commandRetries--
                log(String.format("FT: no ACK for command 0x%02X, send it again", command))
                handler.removeCallbacks(timer)
                handler.postDelayed({ if (op === this) sendCommand() }, SETTLE_MS)
                return
            }
            fail("No answer from the FlySight")
        }
    }

    private inner class SimpleOp(command: Int, done: (Result<Unit>) -> Unit) : Op<Unit>(command, done) {
        override fun onAck() = finish(Unit)
    }

    private inner class ListOp(done: (Result<List<FileInfo>>) -> Unit) : Op<List<FileInfo>>(P.FT_LIST, done) {
        private val entries = mutableListOf<FileInfo>()

        override fun onAck() = armTimer(DATA_TIMEOUT_MS)

        fun onInfo(packet: ByteArray) {
            val info = FileInfo.parse(packet)
            if (info == null) {
                finish(entries.toList())
            } else {
                entries += info
                armTimer(DATA_TIMEOUT_MS)
            }
        }
    }

    private inner class ReadOp(
        private val progress: (Int) -> Unit,
        done: (Result<ByteArray>) -> Unit,
    ) : Op<ByteArray>(P.FT_READ, done) {
        private val buffer = ByteArrayOutputStream()
        private var expected = 0

        override fun onAck() = armTimer(DATA_TIMEOUT_MS)

        fun onData(packet: ByteArray) {
            if (packet.size < 2) return
            val counter = packet[1].toInt() and 0xff
            if (counter == (expected and 0xff)) {
                armTimer(DATA_TIMEOUT_MS)
                ack(counter)
                expected++
                if (packet.size == 2) {
                    // A packet with no data is the end of the file
                    finish(buffer.toByteArray())
                } else {
                    buffer.write(packet, 2, packet.size - 2)
                    progress(buffer.size())
                }
            } else if (((expected - counter) and 0xff) in 1..P.WINDOW_LENGTH) {
                // A packet again: the FlySight did not get our ACK. Send it again.
                ack(counter)
            }
            // Other packets are out of order. Go-Back-N sends them again. They do
            // not reset the timer, thus a read with no progress stops.
        }

        private fun ack(counter: Int) = send(byteArrayOf(P.FT_FILE_ACK.toByte(), counter.toByte()))
    }

    private inner class WriteOp(
        private val data: ByteArray,
        private val progress: (Int, Int) -> Unit,
        done: (Result<Unit>) -> Unit,
    ) : Op<Unit>(P.FT_WRITE, done) {
        private val chunkSize = minOf(P.FRAME_LENGTH, connection.mtu - 3 - 2)
        private val chunkCount = (data.size + chunkSize - 1) / chunkSize

        /** Data packets plus one empty packet that marks the end of the file. */
        private val total = chunkCount + 1
        private var base = 0
        private var next = 0
        private var retries = 0

        override fun onAck() {
            log("FT: write open, ${data.size} bytes in packets of $chunkSize bytes")
            pump()
        }

        private fun packet(index: Int): ByteArray {
            val header = byteArrayOf(P.FT_FILE_DATA.toByte(), index.toByte())
            if (index >= chunkCount) return header
            val from = index * chunkSize
            return header + data.copyOfRange(from, minOf(data.size, from + chunkSize))
        }

        private fun pump() {
            while (next < base + P.WINDOW_LENGTH && next < total) {
                send(packet(next))
                next++
            }
            armTimer(WRITE_ACK_TIMEOUT_MS)
        }

        fun onFileAck(counter: Int) {
            // The FlySight acknowledges only packets in sequence, so an ACK confirms all earlier packets
            for (k in base until next) {
                if ((k and 0xff) == counter) {
                    base = k + 1
                    retries = 0
                    progress(minOf(data.size, base * chunkSize), data.size)
                    if (base == total) finish(Unit) else pump()
                    return
                }
            }
        }

        override fun onNak(command: Int) {
            if (command == P.FT_FILE_DATA && base == total - 1) {
                // The ACK of the end packet was lost, and the FlySight closed the file
                finish(Unit)
            } else {
                super.onNak(command)
            }
        }

        override fun onTimeout() {
            if (!acknowledged || ++retries > MAX_RETRIES) {
                super.onTimeout()
                return
            }
            log("FT: no ACK for packet $base, send again (retry $retries)")
            next = base
            pump()
        }
    }

    private companion object {
        const val COMMAND_TIMEOUT_MS = 5_000L
        const val RETRY_COMMAND_TIMEOUT_MS = 2_500L
        const val SETTLE_MS = 300L
        const val DATA_TIMEOUT_MS = 5_000L
        const val WRITE_ACK_TIMEOUT_MS = 2_000L
        const val MAX_RETRIES = 8
    }
}

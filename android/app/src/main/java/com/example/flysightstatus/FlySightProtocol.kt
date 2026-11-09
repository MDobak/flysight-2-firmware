package com.example.flysightstatus

import java.util.Locale
import java.util.UUID

/**
 * BLE identifiers and data formats of the FlySight 2 firmware (develop branch).
 * See Docs/ble.md in the firmware repository. All integers are little endian.
 */
object FlySightProtocol {
    /** Company ID in the manufacturer specific data of FlySight advertising packets. */
    const val MANUFACTURER_ID = 0x09DB

    val BATTERY_SERVICE: UUID = uuid16(0x180F)
    val BATTERY_LEVEL: UUID = uuid16(0x2A19)

    val DEVICE_INFORMATION_SERVICE: UUID = uuid16(0x180A)
    val MANUFACTURER_NAME: UUID = uuid16(0x2A29)
    val MODEL_NUMBER: UUID = uuid16(0x2A24)
    val FIRMWARE_REVISION: UUID = uuid16(0x2A26)

    val FILE_TRANSFER_SERVICE: UUID = UUID.fromString("00000000-cc7a-482a-984a-7f2ed5b3e58f")
    val FT_PACKET_OUT: UUID = UUID.fromString("00000001-8e22-4541-9d4c-21edae82ed19")
    val FT_PACKET_IN: UUID = UUID.fromString("00000002-8e22-4541-9d4c-21edae82ed19")

    val SENSOR_DATA_SERVICE: UUID = UUID.fromString("00000001-cc7a-482a-984a-7f2ed5b3e58f")
    val SD_GNSS_MEASUREMENT: UUID = UUID.fromString("00000000-8e22-4541-9d4c-21edae82ed19")
    val SD_CONTROL_POINT: UUID = UUID.fromString("00000006-8e22-4541-9d4c-21edae82ed19")
    /** Latest values of the other sensors. Only firmware with sensor mode has it. */
    val SD_SENSOR_MEASUREMENT: UUID = UUID.fromString("00000008-8e22-4541-9d4c-21edae82ed19")

    val STARTER_PISTOL_SERVICE: UUID = UUID.fromString("00000002-cc7a-482a-984a-7f2ed5b3e58f")
    val SP_CONTROL_POINT: UUID = UUID.fromString("00000003-8e22-4541-9d4c-21edae82ed19")
    val SP_RESULT: UUID = UUID.fromString("00000004-8e22-4541-9d4c-21edae82ed19")

    val DEVICE_STATE_SERVICE: UUID = UUID.fromString("00000003-cc7a-482a-984a-7f2ed5b3e58f")
    val DS_MODE: UUID = UUID.fromString("00000005-8e22-4541-9d4c-21edae82ed19")
    val DS_CONTROL_POINT: UUID = UUID.fromString("00000007-8e22-4541-9d4c-21edae82ed19")

    /** Client Characteristic Configuration Descriptor (enables notifications and indications). */
    val CCCD: UUID = uuid16(0x2902)

    /** The firmware supports an ATT MTU of up to 250. File packets need 247. */
    const val REQUESTED_MTU = 247

    // ---- File transfer (crs.c) ----

    /** Maximum file data in one 0x10 packet (FRAME_LENGTH in crs.c). */
    const val FRAME_LENGTH = 242

    /** Go-Back-N window (FS_CRS_WINDOW_LENGTH). */
    const val WINDOW_LENGTH = 8

    const val FT_DELETE = 0x01
    const val FT_READ = 0x02
    const val FT_WRITE = 0x03
    const val FT_MKDIR = 0x04
    const val FT_LIST = 0x05
    const val FT_FILE_DATA = 0x10
    const val FT_FILE_INFO = 0x11
    const val FT_FILE_ACK = 0x12
    const val FT_NAK = 0xF0
    const val FT_ACK = 0xF1

    /** The FlySight disconnects after 30 s without a write to FT_Packet_In. A ping resets this timeout. */
    const val FT_PING = 0xFE
    const val FT_CANCEL = 0xFF

    // ---- Control points (control_point_protocol.h) ----

    const val CP_RESPONSE_ID = 0xF0

    const val SD_CMD_SET_MASK = 0x01
    const val SD_CMD_GET_MASK = 0x02

    const val SP_CMD_START_COUNTDOWN = 0x01
    const val SP_CMD_CANCEL_COUNTDOWN = 0x02

    const val DS_CMD_GET_FW_VERSION = 0x01
    const val DS_CMD_REBOOT = 0x02
    const val DS_CMD_GET_DEVICE_ID = 0x03
    const val DS_CMD_INSTALL_FIRMWARE = 0x04
    const val DS_CMD_REQUEST_SLEEP = 0x10
    const val DS_CMD_REQUEST_ACTIVE = 0x11
    const val DS_CMD_REQUEST_START = 0x12
    const val DS_CMD_REQUEST_CONFIG = 0x13
    const val DS_CMD_REQUEST_PAIRING = 0x14
    const val DS_CMD_REQUEST_SENSOR = 0x15
    const val DS_CMD_SET_ALT_ZERO = 0x16
    const val DS_CMD_GET_ALT_ZERO = 0x17

    val DS_COMMAND_NAMES = mapOf(
        DS_CMD_GET_FW_VERSION to "Get firmware version",
        DS_CMD_REBOOT to "Reboot",
        DS_CMD_GET_DEVICE_ID to "Get device ID",
        DS_CMD_INSTALL_FIRMWARE to "Install uploaded firmware",
        DS_CMD_REQUEST_SLEEP to "Request idle (sleep)",
        DS_CMD_REQUEST_ACTIVE to "Request active",
        DS_CMD_REQUEST_START to "Request start",
        DS_CMD_REQUEST_CONFIG to "Request config",
        DS_CMD_REQUEST_PAIRING to "Request pairing",
        DS_CMD_REQUEST_SENSOR to "Request sensor",
        DS_CMD_SET_ALT_ZERO to "Set ground level",
        DS_CMD_GET_ALT_ZERO to "Get ground level",
    )

    val SD_COMMAND_NAMES = mapOf(SD_CMD_SET_MASK to "Set GNSS mask", SD_CMD_GET_MASK to "Get GNSS mask")

    val SP_COMMAND_NAMES = mapOf(
        SP_CMD_START_COUNTDOWN to "Start countdown",
        SP_CMD_CANCEL_COUNTDOWN to "Cancel countdown",
    )

    // ---- GNSS data mask (gnss_ble.h) ----

    const val GNSS_BIT_TOW = 0x80
    const val GNSS_BIT_WEEK = 0x40
    const val GNSS_BIT_POSITION = 0x20
    const val GNSS_BIT_VELOCITY = 0x10
    const val GNSS_BIT_ACCURACY = 0x08
    const val GNSS_BIT_NUM_SV = 0x04

    const val GNSS_DEFAULT_MASK = 0xB0

    /** GNSS fields for the Sensors screen: time, position, velocity, accuracy, and satellites. */
    const val GNSS_SENSORS_MASK = 0xBC

    // ---- Sensor data mask (sensor_ble.h) ----

    const val SENSOR_BIT_BARO = 0x01
    const val SENSOR_BIT_HUM = 0x02
    const val SENSOR_BIT_MAG = 0x04
    const val SENSOR_BIT_IMU = 0x08
    const val SENSOR_BIT_VBAT = 0x10

    private fun uuid16(id: Int): UUID =
        UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", id))
}

/** Values of the DS_Mode characteristic (FS_Mode_State_t in the firmware). */
enum class DeviceMode(val label: String) {
    SLEEP("Idle"),
    ACTIVE("Active"),
    CONFIG("Config"),
    USB("USB"),
    PAIRING("Pairing"),
    START("Start"),
    SENSOR("Sensor"),
    UNKNOWN("Unknown");

    companion object {
        fun from(value: Int) = entries.getOrNull(value)?.takeIf { it != UNKNOWN } ?: UNKNOWN
    }
}

/** Little-endian reader for packet data. */
class PacketReader(private val data: ByteArray, var position: Int = 0) {
    val remaining get() = data.size - position

    fun u8(): Int = data[position++].toInt() and 0xff
    fun u16(): Int = u8() or (u8() shl 8)
    fun s16(): Int = u16().toShort().toInt()
    fun s32(): Int = u8() or (u8() shl 8) or (u8() shl 16) or (u8() shl 24)
    fun u32(): Long = s32().toLong() and 0xffffffffL
}

/** Response on a control point: [0xF0] [request opcode] [status] [optional data]. */
data class CpResponse(val opcode: Int, val status: Int, val data: ByteArray) {
    val success get() = status == 0x01

    val statusText: String
        get() = when (status) {
            0x01 -> "Success"
            0x02 -> "Command not supported"
            0x03 -> "Invalid parameter"
            0x04 -> "Operation failed"
            0x05 -> "Operation not permitted"
            0x06 -> "Busy"
            else -> String.format(Locale.US, "Error 0x%02X", status)
        }

    /** Optional data as text, without the terminating zero. */
    val dataText: String get() = data.toString(Charsets.ISO_8859_1).trimEnd('\u0000')

    companion object {
        fun parse(value: ByteArray): CpResponse? {
            if (value.size < 3 || (value[0].toInt() and 0xff) != FlySightProtocol.CP_RESPONSE_ID) return null
            return CpResponse(value[1].toInt() and 0xff, value[2].toInt() and 0xff, value.copyOfRange(3, value.size))
        }
    }
}

/** Response data of DS_CMD_SET_ALT_ZERO and DS_CMD_GET_ALT_ZERO. */
data class AltZero(
    /** Parts that the command set: [GNSS] and [BARO]. */
    val set: Int,
    val flags: Int,
    val groundHmslM: Double,
    val groundPressurePa: Double,
    /** From the last configuration that the firmware read. */
    val altSource: Int,
    val altZero: Int,
    /** DZ_Elev (m) of the last configuration. Null for old firmware. */
    val dzElevM: Double?,
) {
    val hasGnss get() = flags and GNSS != 0
    val hasBaro get() = flags and BARO != 0
    val automatic get() = flags and AUTO != 0

    companion object {
        const val GNSS = 0x01
        const val BARO = 0x02
        const val AUTO = 0x04

        fun parse(data: ByteArray): AltZero? = try {
            val r = PacketReader(data)
            AltZero(r.u8(), r.u8(), r.s32() / 1000.0, r.s32() / 100.0, r.u8(), r.u8(),
                if (r.remaining >= 4) r.s32() / 1000.0 else null)
        } catch (_: Exception) {
            null
        }

        /** Standard-atmosphere altitude (m) of a pressure (Pa). */
        fun pressureAltitude(pa: Double) = 44330.77 * (1 - Math.pow(pa / 101325.0, 0.190263))
    }
}

/** One SD_GNSS_Measurement notification. Fields are null if the mask does not include them. */
data class GnssMeasurement(
    val mask: Int,
    val iTowMs: Long?,
    val lonDeg: Double?,
    val latDeg: Double?,
    val hMslM: Double?,
    val velN: Double?,
    val velE: Double?,
    val velD: Double?,
    val hAccM: Double?,
    val vAccM: Double?,
    val sAccMs: Double?,
    val numSv: Int?,
) {
    companion object {
        fun parse(value: ByteArray): GnssMeasurement? = try {
            val r = PacketReader(value)
            val mask = r.u8()
            val tow = if (mask and FlySightProtocol.GNSS_BIT_TOW != 0) r.u32() else null
            // The firmware does not send week data yet (GNSS_BIT_WEEK)
            var lon: Double? = null
            var lat: Double? = null
            var h: Double? = null
            if (mask and FlySightProtocol.GNSS_BIT_POSITION != 0) {
                lon = r.s32() / 1e7
                lat = r.s32() / 1e7
                h = r.s32() / 1000.0
            }
            var vn: Double? = null
            var ve: Double? = null
            var vd: Double? = null
            if (mask and FlySightProtocol.GNSS_BIT_VELOCITY != 0) {
                vn = r.s32() / 1000.0
                ve = r.s32() / 1000.0
                vd = r.s32() / 1000.0
            }
            var ha: Double? = null
            var va: Double? = null
            var sa: Double? = null
            if (mask and FlySightProtocol.GNSS_BIT_ACCURACY != 0) {
                ha = r.u32() / 1000.0
                va = r.u32() / 1000.0
                sa = r.u32() / 1000.0
            }
            val sv = if (mask and FlySightProtocol.GNSS_BIT_NUM_SV != 0) r.u8() else null
            GnssMeasurement(mask, tow, lon, lat, h, vn, ve, vd, ha, va, sa, sv)
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }
}

/**
 * One SD_Sensor_Measurement notification. The mask tells which sensors have
 * new data. Fields of the other sensors are null.
 */
data class SensorMeasurement(
    val mask: Int,
    val pressurePa: Double? = null,
    val baroTempC: Double? = null,
    val humidity: Double? = null,
    val humTempC: Double? = null,
    /** Magnetic field in gauss. */
    val mag: Triple<Double, Double, Double>? = null,
    val magTempC: Double? = null,
    /** Angular rate in deg/s. */
    val gyro: Triple<Double, Double, Double>? = null,
    /** Acceleration in g. */
    val accel: Triple<Double, Double, Double>? = null,
    val imuTempC: Double? = null,
    val batteryV: Double? = null,
) {
    companion object {
        fun parse(value: ByteArray): SensorMeasurement? = try {
            val r = PacketReader(value)
            val mask = r.u8()
            var m = SensorMeasurement(mask)
            if (mask and FlySightProtocol.SENSOR_BIT_BARO != 0) {
                m = m.copy(pressurePa = r.s32() / 100.0, baroTempC = r.s16() / 100.0)
            }
            if (mask and FlySightProtocol.SENSOR_BIT_HUM != 0) {
                m = m.copy(humidity = r.u16() / 10.0, humTempC = r.s16() / 10.0)
            }
            if (mask and FlySightProtocol.SENSOR_BIT_MAG != 0) {
                m = m.copy(mag = Triple(r.s16() / 1000.0, r.s16() / 1000.0, r.s16() / 1000.0), magTempC = r.s16() / 10.0)
            }
            if (mask and FlySightProtocol.SENSOR_BIT_IMU != 0) {
                val gyro = Triple(r.s32() / 1000.0, r.s32() / 1000.0, r.s32() / 1000.0)
                val accel = Triple(r.s32() / 100000.0, r.s32() / 100000.0, r.s32() / 100000.0)
                m = m.copy(gyro = gyro, accel = accel, imuTempC = r.s16() / 100.0)
            }
            if (mask and FlySightProtocol.SENSOR_BIT_VBAT != 0) {
                m = m.copy(batteryV = r.u16() / 1000.0)
            }
            m
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }
}

/** SP_Result: start time from the starter pistol (UTC). */
data class StartResult(val year: Int, val month: Int, val day: Int, val hour: Int, val minute: Int, val second: Int, val ms: Int) {
    override fun toString() = String.format(
        Locale.US, "%04d-%02d-%02d %02d:%02d:%02d.%03d UTC", year, month, day, hour, minute, second, ms,
    )

    companion object {
        fun parse(value: ByteArray): StartResult? {
            if (value.size < 9) return null
            val r = PacketReader(value)
            return StartResult(r.u16(), r.u8(), r.u8(), r.u8(), r.u8(), r.u8(), r.u16())
        }
    }
}

/** One directory entry from a 0x11 File Info packet (FatFs FILINFO, 8.3 names). */
data class FileInfo(val name: String, val size: Long, val date: Int, val time: Int, val attributes: Int) {
    val isDirectory get() = attributes and 0x10 != 0
    val isHidden get() = attributes and 0x06 != 0

    /** FAT date and time as text. */
    val modified: String
        get() = if (date == 0) "" else String.format(
            Locale.US, "%04d-%02d-%02d %02d:%02d",
            1980 + (date shr 9), (date shr 5) and 0x0f, date and 0x1f,
            time shr 11, (time shr 5) and 0x3f,
        )

    override fun toString(): String =
        if (isDirectory) "$name/" else "$name\n${formatSize(size)}  ·  $modified"

    companion object {
        /** Returns null for the end-of-list packet (empty name). */
        fun parse(packet: ByteArray): FileInfo? {
            if (packet.size < 12) return null
            val r = PacketReader(packet, 2)
            val size = r.u32()
            val date = r.u16()
            val time = r.u16()
            val attributes = r.u8()
            val raw = packet.copyOfRange(11, minOf(packet.size, 24))
            val end = raw.indexOf(0).let { if (it < 0) raw.size else it }
            if (end == 0) return null
            return FileInfo(String(raw, 0, end, Charsets.ISO_8859_1), size, date, time, attributes)
        }

        fun formatSize(size: Long): String = when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", size / 1024.0)
            else -> String.format(Locale.US, "%.1f MB", size / (1024.0 * 1024.0))
        }
    }
}

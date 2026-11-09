package com.example.flysightstatus

import android.text.InputType
import java.util.Locale
import kotlin.math.abs

/**
 * The settings of the FlySight config file, from FlySight/config.c.
 * Defaults are the values that the firmware uses when the key is not in
 * CONFIG.TXT (FS_Config_Init), written as file values.
 */
object ConfigSchema {

    sealed class Field(val key: String, val label: String, val help: String?) {
        /** A longer explanation. The form shows it in a dialog from an info button. */
        var more: String? = null
    }

    private fun <T : Field> T.more(text: String): T = apply { more = text }

    class Choice(key: String, label: String, val options: List<Pair<Int, String>>, val default: Int, help: String? = null) :
        Field(key, label, help) {
        fun labelOf(value: Int?) = options.firstOrNull { it.first == value }?.second ?: value?.toString() ?: "–"

        /** Few short options fit in a segmented button. More options use a menu. */
        val segmented get() = options.size <= 3 && options.sumOf { it.second.length } <= 22
    }

    /**
     * How the app shows a number. The file value × [scale] is the value that the
     * user sees, for example cm/s × 0.036 = km/h. [trim] removes zeros at the end.
     */
    class Display(
        val unit: String = "",
        val scale: Double = 1.0,
        val decimals: Int = 0,
        val trim: Boolean = false,
        val note: ((Int) -> String?)? = null,
    ) {
        private fun number(raw: Int, group: Boolean): String {
            val v = raw * scale
            if (decimals == 0) return if (group) TrackStore.formatInt(v) else Math.round(v).toString()
            val s = String.format(Locale.US, "%.${decimals}f", v)
            return if (trim) s.trimEnd('0').trimEnd('.') else s
        }

        /** The value for a text field: no unit and no digit groups. */
        fun plain(raw: Int) = number(raw, group = false)

        /** The value with its unit. */
        fun text(raw: Int): String {
            val n = number(raw, group = true)
            return when (unit) {
                "" -> n
                "°" -> "$n°"
                else -> "$n $unit"
            }
        }

        /** The file value for a value that the user typed, or null. */
        fun parse(s: String): Int? {
            val d = s.trim().replace(',', '.').replace(" ", "").toDoubleOrNull() ?: return null
            val r = Math.round(d / scale)
            return if (r in Int.MIN_VALUE..Int.MAX_VALUE) r.toInt() else null
        }

        val inputType: Int
            get() = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or
                (if (scale != 1.0) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
    }

    /** A number. [display] can depend on other values in the file. [range] is in file values. */
    class Number(
        key: String,
        label: String,
        val default: Int,
        val range: IntRange? = null,
        help: String? = null,
        val display: (ConfigFile) -> Display = { PLAIN },
    ) : Field(key, label, help)

    class Slider(
        key: String,
        label: String,
        val default: Int,
        val range: IntRange,
        help: String? = null,
        /** The value as text, for example "6 of 8" or "Every 5 s". */
        val format: (Int) -> String = { if (it == 0) "Off" else "$it of ${range.last}" },
    ) : Field(key, label, help)

    class Toggle(key: String, label: String, val default: Boolean, help: String? = null) : Field(key, label, help)

    /** Text. With [audio], the value is the name of a WAV file in /audio, without the extension. */
    class Text(key: String, label: String, val maxLength: Int, help: String? = null, val audio: Boolean = false) : Field(key, label, help)

    /** Lat and Lon together, selected on a map. */
    object Target : Field("Lat", "Target", "The destination for navigation.") {
        init {
            more = "Direction to destination, Distance to destination, and Altitude at destination use this point. " +
                "Tap the setting to select the point on a map."
        }
    }

    /** A list, for example the alarms. Each item has the [fields]. The first field starts an item. */
    class Group(
        val title: String,
        val itemName: String,
        val fields: List<Field>,
        val max: Int,
        val newItem: Map<String, String>,
        val help: String? = null,
        /** A second line for an item, or null. */
        val detail: (Map<String, String>, ConfigFile) -> String? = { _, _ -> null },
        val describe: (Map<String, String>) -> String,
    ) {
        val keys = fields.map { it.key }
    }

    /** A part of the form. [summary] shows the important values when the section is closed. */
    class Section(
        val title: String,
        val icon: Int,
        val items: List<Any>,
        val note: String? = null,
        val summary: (ConfigFile) -> String,
    )

    // ---- Options ----

    private val VALUE_MODES = listOf(
        0 to "Horizontal speed",
        1 to "Vertical speed",
        2 to "Glide ratio",
        3 to "Inverse glide ratio",
        4 to "Total speed",
        5 to "Direction to destination",
        6 to "Distance to destination",
        7 to "Direction to bearing",
        11 to "Dive angle",
    )
    private val SPEECH_MODES = VALUE_MODES + (12 to "Altitude above ground")
    private val RATE_MODES = VALUE_MODES.filter { it.first <= 4 } +
        listOf(8 to "Magnitude of value 1", 9 to "Change in value 1", 11 to "Dive angle")
    /** The ActiveLook altitude is the GPS altitude above the Ground elevation. */
    private val AL_LINES = VALUE_MODES + listOf(12 to "Altitude above Ground elevation", 13 to "Course", 14 to "Altitude at destination")
    private val UNITS = listOf(0 to "km/h and m", 1 to "mph and ft")

    // ---- Units ----

    private fun f(format: String, vararg args: Any) = String.format(Locale.US, format, *args)

    private val PLAIN = Display()
    private val SPEED = Display("km/h", 0.036, 1, trim = true, note = { v -> f("%.1f mph", v * 0.0223694) })
    private val RATIO = Display("", 0.01, 2)
    private val PERCENT = Display("%", 0.01, 2, trim = true)
    private val HERTZ = Display("Hz", 0.01, 2, trim = true)
    private val METRES = Display("m")
    private val DEGREES = Display("°")
    private val SECONDS = Display("s")

    /** The display of a value for a tone, speech, or rate mode. */
    private fun valueDisplay(mode: Int?): Display = when (mode) {
        0, 1, 4 -> SPEED
        2, 3 -> RATIO
        5, 7, 11 -> DEGREES
        6 -> METRES
        9 -> PERCENT
        else -> PLAIN
    }

    private fun mode(file: ConfigFile) = file.getInt("Mode") ?: 2

    private fun mode2(file: ConfigFile): Int? = when (val m = file.getInt("Mode_2") ?: 9) {
        8 -> mode(file)
        else -> m
    }

    private fun timeZone(seconds: Int): String {
        val sign = if (seconds < 0) "−" else "+"
        val s = abs(seconds)
        return f("UTC%s%d:%02d", sign, s / 3600, s / 60 % 60)
    }

    fun altitudeUnit(file: ConfigFile) = if ((file.getInt("Alt_Units") ?: 1) == 0) "m" else "ft"

    // ---- Values as text ----

    /** A short text for the value of [field]. A missing value gives the default. */
    fun valueText(field: Field, raw: String?, file: ConfigFile): String = when (field) {
        is Choice -> field.labelOf(raw?.let { ConfigFile.parseInt(it) } ?: field.default)
        is Number -> field.display(file).text(raw?.let { ConfigFile.parseInt(it) } ?: field.default)
        is Slider -> field.format(raw?.let { ConfigFile.parseInt(it) } ?: field.default)
        is Toggle -> if ((raw?.let { ConfigFile.parseInt(it) } ?: if (field.default) 1 else 0) != 0) "On" else "Off"
        is Text -> raw ?: "None"
        Target -> {
            val lat = file.getInt("Lat")
            val lon = file.getInt("Lon")
            if (lat == null || lon == null || (lat == 0 && lon == 0)) "Not set" else f("%.5f, %.5f", lat / 1e7, lon / 1e7)
        }
    }

    private fun unitsAndDecimals(units: String?, decimals: String?): String {
        val u = UNITS.firstOrNull { it.first == units?.let(ConfigFile::parseInt) }?.second ?: "mph and ft"
        val d = decimals?.let(ConfigFile::parseInt) ?: 1
        return "$u · $d decimal ${if (d == 1) "place" else "places"}"
    }

    private fun v(file: ConfigFile, key: String): String =
        fields.firstOrNull { it.key == key }?.let { valueText(it, file.get(key), file) } ?: ""

    private fun count(file: ConfigFile, key: String, one: String, many: String, none: String): String {
        val n = file.group(groups.first { key in it.keys }.keys).size
        return when (n) {
            0 -> none
            1 -> "1 $one"
            else -> "$n $many"
        }
    }

    // ---- Speech examples ----

    private val ONES = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
    private val TEENS = listOf("ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    /** A number in words, as numberToSpeech in FlySight/audio_control.c builds it. */
    private fun words(n: Int): String {
        if (n == 0) return "zero"
        if (n < 0) return "minus " + words(-n)
        val out = mutableListOf<String>()
        var x = n
        if (x / 1000 > 0) { out += words(x / 1000); out += "thousand"; x %= 1000 }
        if (x / 100 > 0) { out += words(x / 100); out += "hundred"; x %= 100 }
        when {
            x == 0 -> Unit
            x < 10 -> out += ONES[x]
            x < 20 -> out += TEENS[x - 10]
            else -> { out += TENS[x / 10]; if (x % 10 > 0) out += ONES[x % 10] }
        }
        return out.joinToString(" ")
    }

    /** A number as single digits, as the FlySight speaks values. The new firmware does not speak the decimal point. */
    private fun digits(v: Double, decimals: Int): String =
        // The FlySight cuts the extra decimal places. It does not round them.
        java.math.BigDecimal(v).setScale(decimals, java.math.RoundingMode.DOWN).toPlainString().filter { it != '.' }.map { c ->
            when (c) {
                '-' -> "minus"
                else -> ONES[c - '0']
            }
        }.joinToString(" ")

    private fun shortAltitude(file: ConfigFile) = (file.getInt("Alt_Format") ?: 0) == 1

    /**
     * An altitude as the FlySight speaks it, as speakAltitude in
     * FlySight/audio_control.c builds it: "alti" and the value, with no unit.
     */
    fun altitudeSpeech(value: Int, short: Boolean): String = "alti " +
        if (short && value >= 1000 && value % 100 == 0) (value / 100).toString().map { ONES[it - '0'] }.joinToString(" ")
        else words(value)

    /** The name that the FlySight speaks before each spoken value. */
    private val SPEECH_LABELS = mapOf(0 to "H speed", 1 to "V speed", 2 to "glide", 3 to "inverse glide", 4 to "speed",
        5 to "direction", 6 to "distance", 7 to "bearing", 11 to "dive")

    /** An example of the speech for one spoken value. */
    private fun speechExample(mode: Int?, units: Int?, decimals: Int?, shortAltitude: Boolean): String {
        val imperial = (units ?: 1) == 1
        val dec = (decimals ?: 1).coerceIn(0, 2)
        val speed = { kmh: Double -> digits(if (imperial) kmh / 1.609344 else kmh, dec) }
        val text = when (mode) {
            0 -> speed(150.0)
            1 -> speed(185.0)
            2 -> digits(2.5, dec)
            3 -> digits(0.4, dec)
            4 -> speed(240.0)
            5 -> digits(45.0, 0) + " left"
            6 -> digits(if (imperial) 0.75 else 1.2, 1) + if (imperial) " miles" else " kilometers"
            7 -> digits(30.0, 0) + " right"
            11 -> digits(35.0, dec)
            12 -> {
                val step = decimals ?: 1
                if (step <= 0) return "Set a step of 1 or more."
                val h = if (imperial) 4049.0 else 1234.0
                altitudeSpeech((Math.round(h / step) * step).toInt(), shortAltitude)
            }
            else -> return ""
        }.let { if (mode == 12) it else "${SPEECH_LABELS[mode]} $it" }
        return "For example: \"$text\""
    }

    // ---- Sections ----

    private val VOLUME = { v: Int -> "$v of 8" }

    private val BARO_RATES = listOf(0 to "One shot", 1 to "1 Hz", 2 to "10 Hz", 3 to "25 Hz", 4 to "50 Hz", 5 to "75 Hz", 6 to "100 Hz", 7 to "200 Hz")
    private val HUM_RATES = listOf(0 to "One shot", 1 to "1 Hz", 2 to "7 Hz", 3 to "12.5 Hz")
    private val MAG_RATES = listOf(0 to "10 Hz", 1 to "20 Hz", 2 to "50 Hz", 3 to "100 Hz")
    private val IMU_RATES = listOf(1 to "12.5 Hz", 2 to "26 Hz", 3 to "52 Hz", 4 to "104 Hz", 5 to "208 Hz",
        6 to "416 Hz", 7 to "833 Hz", 8 to "1666 Hz", 9 to "3333 Hz", 10 to "6666 Hz")

    val sections = listOf(
        Section("GNSS", R.drawable.ic_s_gnss, listOf(
            Choice("Model", "Dynamic model", listOf(
                0 to "Portable", 2 to "Stationary", 3 to "Pedestrian", 4 to "Automotive", 5 to "Sea",
                6 to "Airborne, less than 1 g", 7 to "Airborne, less than 2 g", 8 to "Airborne, less than 4 g",
            ), 7, "The type of movement that the receiver expects.").more(
                "When the signal is weak, the receiver expects this type of movement. It filters other movement as an error. " +
                    "For example, with Pedestrian, a fast fall looks like an error.\n\n" +
                    "For skydiving, use an Airborne model. Use 2 g or 4 g for strong accelerations, " +
                    "for example a wingsuit flare or a fast canopy landing."),
            Number("Rate", "Measurement interval", 200, 40..1000, "Time between two measurements.",
                display = { Display("ms", note = { v -> if (v > 0) f("%.1f Hz", 1000.0 / v) else null }) }).more(
                "A shorter interval gives more measurements in the log, but uses more battery and more storage. " +
                    "The default is 200 ms (5 measurements each second). The permitted range is 40 ms to 1000 ms."),
        )) { c -> "${v(c, "Model")} · ${v(c, "Rate")}" },

        Section("Pitch", R.drawable.ic_s_tone, listOf(
            Choice("Mode", "Pitch from", VALUE_MODES, 2, "The value that sets the pitch of the tone. From the GPS.").more(
                "The FlySight plays a tone from 220 Hz to 1760 Hz. The value at \"Lowest pitch at\" gives the lowest tone. " +
                    "The value at \"Highest pitch at\" gives the highest tone. A value between them gives a tone between them.\n\n" +
                    "Glide ratio is the horizontal speed divided by the vertical speed. " +
                    "Inverse glide ratio is the vertical speed divided by the horizontal speed. " +
                    "Dive angle is the angle of your path below the horizon.\n\n" +
                    "All these values come from the GPS. The tone plays only with a GPS fix."),
            Number("Min", "Lowest pitch at", 0, help = "The value for the lowest tone.", display = { valueDisplay(mode(it)) }).more(RANGE_TIP),
            Number("Max", "Highest pitch at", 300, help = "The value for the highest tone.", display = { valueDisplay(mode(it)) }).more(RANGE_TIP),
            Choice("Limits", "Outside the range", listOf(
                0 to "No tone", 1 to "Lowest or highest tone", 2 to "Chirp up and down", 3 to "Chirp down and up",
            ), 1, "The sound when the value is not in the range.").more(
                "No tone: the FlySight is silent.\n\n" +
                    "Lowest or highest tone: the tone stays at the end of the range.\n\n" +
                    "Chirp up and down: below the range, the tone goes up quickly. Above the range, it goes down quickly. " +
                    "Chirp down and up does the opposite.\n\n" +
                    "A chirp tells you clearly that you are out of the range, for example when you keep the vertical speed in a range."),
            Slider("Volume", "Tone volume", 6, 0..8, "Also the volume of the beep and chirp alarms.", VOLUME),
            Toggle("Use_SAS", "Skydiver's airspeed", true, "Change the tone and speech speeds to sea-level speeds.").more(
                "The air is thinner at altitude, so you fall faster in the same body position. " +
                    "With this setting, the FlySight changes the speeds for the tone and the speech to sea-level speeds. " +
                    "Then the tone does not change when you go lower. The FlySight uses the GPS altitude above sea level for this.\n\n" +
                    "The speeds in the log do not change. The speed thresholds use the real speeds."),
        )) { c -> "${v(c, "Mode")} · ${v(c, "Min")} to ${v(c, "Max")}" },

        Section("Repeat rate", R.drawable.ic_s_rate, listOf(
            Choice("Mode_2", "Rate from", RATE_MODES, 9, "The value that sets how fast the tone repeats.").more(
                "With the default settings, the tone repeats 1 time each second when the value is steady. " +
                    "When the value changes quickly, the tone repeats up to 5 times each second. " +
                    "Usually, you do not have to change these settings.\n\n" +
                    "Magnitude of value 1: the rate comes from the same value as the pitch.\n\n" +
                    "Change in value 1: the rate comes from how fast the pitch value changes."),
            Number("Min_Val_2", "Lowest rate at", 300, help = "The value for the lowest rate.", display = { valueDisplay(mode2(it)) }),
            Number("Max_Val_2", "Highest rate at", 1500, help = "The value for the highest rate.", display = { valueDisplay(mode2(it)) }),
            Number("Min_Rate", "Lowest rate", 100, 0..Int.MAX_VALUE, "Tones each second at the lowest value.", display = { HERTZ }),
            Number("Max_Rate", "Highest rate", 500, 0..Int.MAX_VALUE, "Tones each second at the highest value.", display = { HERTZ }),
            Toggle("Flatline", "Flatline", false, "A continuous tone below the lowest rate value.").more(
                "When the rate value is below \"Lowest rate at\", the tone does not repeat. It plays continuously. " +
                    "Use it, for example, to hear when you fly level, or as an alarm for a low total speed."),
        )) { c -> "${v(c, "Mode_2")} · ${v(c, "Min_Rate")} to ${v(c, "Max_Rate")}" },

        Section("Spoken values", R.drawable.ic_s_speech, listOf(
            Slider("Sp_Rate", "Speech interval", 0, 0..32, "Time between two spoken values.") { if (it == 0) "Off" else "Every $it s" }.more(
                "3 s is very frequent. 10 s is not frequent. Set 0 to stop the speech.\n\n" +
                    "If there is more than one spoken value, the FlySight speaks them in turn.\n\n" +
                    "The FlySight speaks only above the speed thresholds, and not near an alarm or in a silence window."),
            Slider("Sp_Volume", "Speech volume", 8, 0..8, "Also the volume of the altitude calls and the alarm files.", VOLUME),
            Group("Speech values", "spoken value", listOf(
                Choice("Sp_Mode", "Value", SPEECH_MODES, 2, "Tap the info button to read how the FlySight speaks each value.").more(SPOKEN_TIP),
                Choice("Sp_Units", "Units", UNITS, 1, "km/h or mph for speeds. Meters or feet for altitude. Kilometers or miles for distance."),
                Number("Sp_Dec", "Decimal places", 1, help = "For Altitude above ground, this is the step.").more(
                    "For glide ratio, 1 decimal place is usually good, for example \"glide two five\" for 2.5. " +
                        "The FlySight does not speak the decimal point. " +
                        "For speeds, use 0 decimal places, for example \"speed two one zero\".\n\n" +
                        "Direction to destination and Direction to bearing always have 0 decimal places. " +
                        "Distance to destination always has 1 decimal place.\n\n" +
                        "For Altitude above ground, this is a step, in meters or feet. The FlySight rounds the altitude to the step. " +
                        "For example, with a step of 100 ft, the FlySight speaks 9 452 ft as \"alti nine thousand five hundred\". " +
                        "Do not use 0 for the altitude."),
            ), 3, mapOf("Sp_Mode" to "2", "Sp_Units" to "0", "Sp_Dec" to "1"),
                help = "The FlySight speaks these values in turn. Maximum 3.",
                detail = { item, file ->
                    val mode = item["Sp_Mode"]?.let(ConfigFile::parseInt)
                    val units = item["Sp_Units"]?.let(ConfigFile::parseInt)
                    val dec = item["Sp_Dec"]?.let(ConfigFile::parseInt)
                    val first = if (mode == 12) "Step ${dec ?: 1} ${if ((units ?: 1) == 1) "ft" else "m"}"
                    else unitsAndDecimals(item["Sp_Units"], item["Sp_Dec"])
                    first + "\n" + speechExample(mode, units, dec, shortAltitude(file))
                }) { item ->
                val m = item["Sp_Mode"]?.let { ConfigFile.parseInt(it) }
                SPEECH_MODES.firstOrNull { it.first == m }?.second ?: "Value $m"
            },
        )) { c ->
            if ((c.getInt("Sp_Rate") ?: 0) == 0) "No speech"
            else "${v(c, "Sp_Rate")} · ${count(c, "Sp_Mode", "value", "values", "No values")}"
        },

        Section("Alarms and altitude calls", R.drawable.ic_s_alarm, listOf(
            Group("Alarms", "alarm", listOf(
                Number("Alarm_Elev", "Height above ground", 1000, help = "In meters, above the ground level.", display = { METRES }).more(
                    "The alarm plays when you go through this height, up or down. " +
                        "It plays at all speeds, also in a silence window.\n\n$HEIGHT_TIP"),
                Choice("Alarm_Type", "Sound", listOf(0 to "No alarm", 1 to "Beep", 2 to "Chirp up", 3 to "Chirp down", 4 to "Play a file"), 1,
                    "Beeps and chirps use the tone volume. Files use the speech volume."),
                Text("Alarm_File", "File to play", 8, "For Play a file. A WAV file in /audio.", audio = true),
            ), 20, mapOf("Alarm_Elev" to "1000", "Alarm_Type" to "1"),
                help = "An alarm plays when you go through its height above the ground level. Maximum 20.",
                detail = { item, _ ->
                    val type = item["Alarm_Type"]?.let { ConfigFile.parseInt(it) } ?: 0
                    listOf("No alarm", "Beep", "Chirp up", "Chirp down", "Play ${item["Alarm_File"] ?: "a file"}").getOrElse(type) { "Type $type" }
                }) { item ->
                "${TrackStore.formatInt((item["Alarm_Elev"]?.let { ConfigFile.parseInt(it) } ?: 0).toDouble())} m"
            },
            Number("Alt_Step", "Altitude calls", 0, 0..Int.MAX_VALUE, "Speak the height above the ground level at each step. 0 = off.",
                display = { Display(altitudeUnit(it)) }).more(
                "For example, with a step of 1 000 ft, the FlySight speaks \"alti twelve thousand\", \"alti eleven thousand\", and so on, with no unit. " +
                    "With the short format, it speaks \"alti one two zero\", \"alti one one zero\", and so on. " +
                    "It does not speak 0 at the ground.\n\n" +
                    "The FlySight speaks a call only above the speed thresholds, and not in a silence window. " +
                    "There is a quiet zone around each call (see Quiet zones).\n\n" +
                    "After power on, when the altitude is accurate, the FlySight speaks the height one time.\n\n$HEIGHT_TIP"),
            Choice("Alt_Units", "Altitude call units", listOf(0 to "m", 1 to "ft"), 1, "For the altitude calls and the step."),
            Choice("Alt_Format", "Altitude call format", listOf(0 to "Full", 1 to "Short"), 0,
                "How the FlySight speaks the altitude.").more(
                "The FlySight speaks \"alti\" and the altitude, with no unit.\n\n" +
                    "Full: the number in words. For example, 2 500 is \"alti two thousand five hundred\".\n\n" +
                    "Short: from 1 000, only the digits of the hundreds. " +
                    "For example, 2 000 is \"alti two zero\", 2 500 is \"alti two five\", and 12 500 is \"alti one two five\". " +
                    "Below 1 000, the FlySight uses the full format, for example \"alti nine hundred\". " +
                    "An altitude that is not a full hundred (for example 2 550 with a step of 50) also uses the full format.\n\n" +
                    "This setting applies to the altitude calls, to the altitude in the spoken values, " +
                    "and to the altitude that the FlySight speaks after power on. " +
                    "It needs the new firmware. The old firmware ignores it and uses the full format."),
        ), "Heights are above the ground level. The Altitude source setting selects the GPS or the barometer.") { c ->
            val alarms = count(c, "Alarm_Elev", "alarm", "alarms", "No alarms")
            val step = c.getInt("Alt_Step") ?: 0
            "$alarms · " + if (step == 0) "No altitude calls" else "Calls every ${v(c, "Alt_Step")}" + if ((c.getInt("Alt_Format") ?: 0) == 1) ", short" else ""
        },

        Section("Silence windows", R.drawable.ic_s_silence, listOf(
            Group("Silence windows", "window", listOf(
                Number("Win_Top", "Top", 2000, help = "In meters, above the ground level.", display = { METRES }),
                Number("Win_Bottom", "Bottom", 1000, help = "In meters, above the ground level.", display = { METRES }),
            ), 2, mapOf("Win_Top" to "2000", "Win_Bottom" to "1000"),
                help = "Maximum 2.") { item ->
                val top = item["Win_Top"]?.let { ConfigFile.parseInt(it) } ?: 0
                val bottom = item["Win_Bottom"]?.let { ConfigFile.parseInt(it) } ?: 0
                "${TrackStore.formatInt(top.toDouble())} m to ${TrackStore.formatInt(bottom.toDouble())} m"
            },
        ), "In a silence window: no tone, no spoken values, and no altitude calls. Alarms play. " +
            "Heights are above the ground level.") { c ->
            count(c, "Win_Top", "window", "windows", "No windows")
        },

        Section("Ground level", R.drawable.ic_s_altitude, listOf(
            Choice("Alt_Source", "Altitude source", listOf(0 to "GPS", 1 to "Barometer"), 0,
                "For alarms, altitude calls, silence windows, and the spoken altitude.").more(
                "GPS: the GPS height above the ground level. It needs a good fix.\n\n" +
                    "Barometer: the change in air pressure since the ground level was set. It is smoother and faster than the GPS. " +
                    "It needs a ground level from \"Automatic ground level\" or from Ground level on the Home tab. " +
                    "Until then, the FlySight uses the GPS.\n\n" +
                    "With the barometer, the alarms, the altitude calls, and the spoken altitude work also without a GPS fix. " +
                    "Without a fix, the barometer gives the vertical speed for the vertical speed threshold, " +
                    "and the horizontal speed threshold is not used. " +
                    "The spoken values are then only the altitude, and the tone is off.\n\n" +
                    "The barometer uses the standard atmosphere. In warm air it shows less height than the real height, " +
                    "in cold air more (approximately 3–5 %). The weather also changes the pressure, " +
                    "so set the ground level again before each jump or use \"Automatic ground level\".\n\n" +
                    "The other functions always use the GPS: the tone, the spoken speeds and glide ratio, " +
                    "Skydiver's airspeed, \"Stop navigation below\", the ActiveLook display, and the log."),
            Choice("Alt_Zero", "Automatic ground level", listOf(0 to "Off", 1 to "On"), 0,
                "Set the ground level after 1 minute with no movement.").more(
                "In active mode, when the FlySight does not move for 1 minute (for example, on the ground before boarding), " +
                    "it sets the ground level to the current GPS altitude and air pressure. " +
                    "While it stays still, it continues to update the ground level.\n\n" +
                    "Off: the FlySight uses the ground level that you set on the Home tab, or else the Ground elevation.\n\n" +
                    "The ground level stays until the FlySight resets. The FlySight keeps it in idle mode."),
            Number("DZ_Elev", "Ground elevation", 0, help = "Elevation of the landing area above sea level. Used when there is no ground level.",
                display = { METRES }).more(
                "The FlySight uses this elevation only when it has no ground level " +
                    "from \"Automatic ground level\" or from Ground level on the Home tab. " +
                    "Then alarms, altitude calls, silence windows, the spoken altitude, and \"Stop navigation below\" " +
                    "are GPS heights above this elevation. The barometer cannot use it.\n\n" +
                    "The altitude lines on the ActiveLook display always use this elevation.\n\n" +
                    "To find it, turn on the FlySight in an open area for some minutes. " +
                    "Then use Ground level on the Home tab, or open the track in the app and read the altitude."),
        )) { c ->
            val source = if ((c.getInt("Alt_Source") ?: 0) == 1) "Barometer" else "GPS"
            val auto = if ((c.getInt("Alt_Zero") ?: 0) == 1) "Automatic ground level" else "Ground elevation ${v(c, "DZ_Elev")}"
            "$source · $auto"
        },

        Section("Speed thresholds", R.drawable.ic_s_speed, listOf(
            Number("V_Thresh", "Minimum vertical speed", 1000, help = "No tone, speech, or altitude call below this vertical speed.",
                display = { SPEED }).more(
                "The default, 36 km/h, removes the sound in the aircraft and under canopy. " +
                    "Set 0 to hear the tone under canopy.\n\n" +
                    "Set both thresholds to 0 to hear the tone on the ground, for example to test the volume.\n\n" +
                    "The speed comes from the GPS. Without a GPS fix and with the barometer as the altitude source, " +
                    "it comes from the barometer. Alarms play at all speeds."),
            Number("H_Thresh", "Minimum horizontal speed", 0, help = "No tone, speech, or altitude call below this horizontal speed.",
                display = { SPEED }).more(
                "The speed comes from the GPS. Without a GPS fix, the FlySight does not use this threshold. " +
                    "Alarms play at all speeds."),
        ), "Below a threshold: no tone, no spoken values, and no altitude calls. Alarms play.") { c ->
            "Vertical ${v(c, "V_Thresh")} · Horizontal ${v(c, "H_Thresh")}"
        },

        Section("Quiet zones", R.drawable.ic_s_silence, listOf(
            Number("Win_Above", "Above each alarm or call", 0, help = "Quiet distance above each alarm and each altitude call.",
                display = { METRES }).more(WINDOW_TIP),
            Number("Win_Below", "Below each alarm or call", 0, help = "Quiet distance below each alarm and each altitude call.",
                display = { METRES }).more(WINDOW_TIP),
        ), "In a quiet zone: no tone and no spoken values. Altitude calls and alarms play.") { c ->
            val above = c.getInt("Win_Above") ?: 0
            val below = c.getInt("Win_Below") ?: 0
            if (above == 0 && below == 0) "Off" else "${v(c, "Win_Above")} above · ${v(c, "Win_Below")} below"
        },

        Section("Navigation", R.drawable.ic_s_navigation, listOf(
            Target,
            Number("Bearing", "Bearing", 0, 0..360, "For Direction to bearing.", display = { DEGREES }).more(
                "A compass direction from 0° to 360°. With Direction to bearing, the tone tells you the difference " +
                    "between your course and this direction."),
            Number("End_Nav", "Stop navigation below", 0, help = "GPS height above the Ground elevation. 0 = no limit.", display = { METRES }).more(
                "Below this height, there is no navigation tone or speech. The height always comes from the GPS, " +
                    "above the Ground elevation (DZ_Elev). The ground level that you set in the app is not used here."),
            Number("Max_Dist", "Maximum distance", 10000, 0..10000, "No navigation tone farther from the target. 0 = no limit.",
                display = { METRES }),
            Number("Min_Angle", "Minimum angle", 5, 0..360, "A smaller difference in direction counts as on course.",
                display = { DEGREES }),
        )) { c -> if (v(c, "Lat") == "Not set") "No target" else "Target ${v(c, "Lat")}" },

        Section("Startup", R.drawable.ic_s_startup, listOf(
            Choice("Init_Mode", "At power on", listOf(0 to "Do nothing", 1 to "Test speech", 2 to "Play a file"), 0,
                "Test speech speaks all digits, so you can examine the audio."),
            Text("Init_File", "File to play", 8, "For Play a file. A WAV file in /audio. In config mode, it is the sound of the profile.", audio = true),
            Number("TZ_Offset", "Time zone of the file names", 0, help = "Changes only the names of the folders and files.",
                display = { Display("h", 1 / 3600.0, 2, trim = true, note = { v -> timeZone(v) }) }).more(
                "The FlySight names the log folders and files from the date and time. " +
                    "With your time zone, a day of jumps does not go into two folders at midnight UTC.\n\n" +
                    "The times in the logs are always UTC."),
        )) { c -> "${v(c, "Init_Mode")} · ${timeZone(c.getInt("TZ_Offset") ?: 0)}" },

        Section("ActiveLook", R.drawable.ic_s_activelook, listOf(
            Choice("AL_Mode", "ActiveLook", listOf(0 to "Off", 1 to "On"), 1, "Show values on ActiveLook glasses."),
            Text("AL_ID", "Glasses ID", 6, "The ID of the glasses."),
            Number("AL_Rate", "Update interval", 1000, 100..Int.MAX_VALUE, "Time between two display updates.", display = { Display("ms") }),
            Number("AltW_Tau", "Glide smoothing", 10, 0..Int.MAX_VALUE, "For Altitude at destination. 0 = no smoothing.", display = { SECONDS }),
            Group("Display lines", "display line", listOf(
                Choice("AL_Line", "Value", AL_LINES, 0),
                Choice("AL_Units", "Units", UNITS, 1),
                Number("AL_Dec", "Decimal places", 1),
            ), 4, mapOf("AL_Line" to "0", "AL_Units" to "0", "AL_Dec" to "1"), help = "Maximum 4.",
                detail = { item, _ -> unitsAndDecimals(item["AL_Units"], item["AL_Dec"]) }) { item ->
                val m = item["AL_Line"]?.let { ConfigFile.parseInt(it) }
                AL_LINES.firstOrNull { it.first == m }?.second ?: "Value $m"
            },
        )) { c -> if ((c.getInt("AL_Mode") ?: 1) == 0) "Off" else count(c, "AL_Line", "line", "lines", "No lines") },

        Section("Sensors and logging", R.drawable.ic_s_sensors, listOf(
            Toggle("Enable_Logging", "Logging", true, "Write the sensor data to the SD card."),
            Toggle("Enable_Audio", "Audio", true, "Tones, speech, and alarms."),
            Toggle("Enable_Gnss", "GNSS", true),
            Toggle("Enable_Baro", "Barometer", true),
            Toggle("Enable_Imu", "IMU", true, "Accelerometer and gyroscope."),
            Toggle("Enable_Mag", "Magnetometer", true),
            Toggle("Enable_Hum", "Humidity", true),
            Toggle("Enable_Mic", "Microphone", true),
            Toggle("Enable_Vbat", "Battery voltage", true),
            Toggle("Enable_Raw", "Raw GNSS data", true, "Also log the raw messages of the GNSS receiver."),
            Toggle("Cold_Start", "GNSS cold start", false, "Clear the GNSS data at power on.").more(
                "After a cold start, the receiver needs more time to find its position. " +
                    "Use it only when the receiver does not find its position."),
            Choice("Baro_ODR", "Barometer rate", BARO_RATES, 2),
            Choice("Hum_ODR", "Humidity rate", HUM_RATES, 1),
            Choice("Mag_ODR", "Magnetometer rate", MAG_RATES, 0),
            Choice("Accel_ODR", "Accelerometer rate", listOf(0 to "Off", 11 to "1.6 Hz") + IMU_RATES, 1),
            Choice("Accel_FS", "Accelerometer range", listOf(0 to "±2 g", 2 to "±4 g", 3 to "±8 g", 1 to "±16 g"), 1),
            Choice("Gyro_ODR", "Gyroscope rate", listOf(0 to "Off") + IMU_RATES, 1),
            Choice("Gyro_FS", "Gyroscope range", listOf(0 to "±250 °/s", 1 to "±500 °/s", 2 to "±1000 °/s", 3 to "±2000 °/s"), 3),
        )) { c ->
            val toggles = fields.filter { it is Toggle && it.key.startsWith("Enable_") }
            val off = toggles.count { v(c, it.key) == "Off" }
            val logging = if (v(c, "Enable_Logging") == "On") "Logging on" else "Logging off"
            "$logging · " + if (off == 0) "All sensors on" else "$off off"
        },
    )

    private const val RANGE_TIP =
        "Set a range that is a little larger than the values that you expect. " +
            "If the range is too large, you hear only a small part of the tones.\n\n" +
            "To find your values, make a jump and examine the track in the app."

    private const val WINDOW_TIP =
        "Near each alarm and each altitude call, the FlySight stops the tone and the spoken values, " +
            "so you hear the alarm or the call clearly. The alarms and the altitude calls always play.\n\n" +
            "For example, with an alarm at 1 000 m and 50 m above, there is no tone from 1 050 m to the alarm. " +
            "For a wingsuit, approximately 50 m above is usually sufficient. Use more if you fall faster.\n\n" +
            "The distance is in meters. Old files use one key, Window, for both values."

    private const val HEIGHT_TIP =
        "The height is above the ground level. The FlySight uses the first that it has:\n" +
            "1. \"Automatic ground level\".\n" +
            "2. Ground level, set on the Home tab.\n" +
            "3. Ground elevation (GPS only).\n\n" +
            "\"Altitude source\" selects the GPS or the barometer. " +
            "The barometer works also without a GPS fix, but it needs 1 or 2."

    private const val SPOKEN_TIP =
        "How the FlySight speaks each value:\n\n" +
            "The FlySight speaks the name of the value, then the value. The digits come one by one, " +
            "with no decimal point and no unit.\n\n" +
            "Horizontal speed, vertical speed, and total speed: \"H speed\", \"V speed\", and \"speed\". " +
            "For example, 185 km/h is \"V speed one eight five\". A climb starts with \"minus\".\n\n" +
            "Glide ratio and inverse glide ratio: \"glide\" and \"inverse glide\". For example, 2.5 is \"glide two five\".\n\n" +
            "Dive angle: \"dive\" and the degrees, for example \"dive three five\".\n\n" +
            "Direction to destination and Direction to bearing: \"direction\" or \"bearing\", the degrees, then \"left\" or \"right\". " +
            "For example, \"direction four five left\".\n\n" +
            "Distance to destination: \"distance\", kilometers or miles with 1 decimal place, and the unit. " +
            "For example, 1.2 km is \"distance one two kilometers\".\n\n" +
            "Altitude above ground: \"alti\" and the number in words, rounded to the step, with no unit. " +
            "For example, \"alti one thousand two hundred\". With the short altitude call format, \"alti one two\". " +
            "It uses the Altitude source setting, " +
            "so with the barometer it works also without a GPS fix.\n\n" +
            "The new firmware needs the sound files HSPEED.WAV and VSPEED.WAV in /audio. ALT.WAV must say \"alti\".\n\n" +
            "All values except the altitude come from the GPS."

    /** The sections in groups of related sections, in the order of the form. */
    val groupedSections: List<Pair<String, List<Section>>> = listOf(
        "Tone and speech" to listOf("Pitch", "Repeat rate", "Spoken values"),
        "Altitude" to listOf("Ground level", "Alarms and altitude calls"),
        "Silence" to listOf("Speed thresholds", "Quiet zones", "Silence windows"),
        "Navigation and display" to listOf("Navigation", "ActiveLook"),
        "Device" to listOf("Startup", "GNSS", "Sensors and logging"),
    ).map { (group, titles) -> group to titles.map { t -> sections.first { it.title == t } } }

    /**
     * The file value that gives the firmware default, or null if the default
     * is no line (a text with no value). Lat and Lon are 0, so the target is not set.
     */
    fun defaultValue(field: Field): String? = when (field) {
        is Choice -> field.default.toString()
        is Number -> field.default.toString()
        is Slider -> field.default.toString()
        is Toggle -> if (field.default) "1" else "0"
        is Text -> null
        Target -> "0"
    }

    /** All single fields, by key (Lat and Lon are one field). */
    val fields: List<Field> = sections.flatMap { s -> s.items.filterIsInstance<Field>() }

    val groups: List<Group> = sections.flatMap { s -> s.items.filterIsInstance<Group>() }

}

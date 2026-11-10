package com.mdobak.flysightstatus

import android.content.Intent
import android.view.View
import java.util.UUID

/** One screen of [MainActivity]: a tab, or a full screen on top of the tabs. */
abstract class Screen(val main: MainActivity) {
    val ui: Ui get() = main.ui

    abstract val view: View

    /** The screen becomes visible. */
    open fun onShow() = Unit

    /** The screen is not visible now: another tab or screen opened, or the app went to the background. */
    open fun onHide() = Unit

    /** The connection, the device data, or the jump download changed. */
    open fun onStateChanged() = Unit

    /** A value from the FlySight. */
    open fun onValue(characteristic: UUID, value: ByteArray) = Unit

    /** Return true if the screen used the Back event. */
    open fun onBack(): Boolean = false

    open fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) = Unit

    /** A new line in the log. */
    open fun onLog(line: String) = Unit
}

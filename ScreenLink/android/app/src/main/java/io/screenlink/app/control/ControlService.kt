package io.screenlink.app.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.screenlink.app.net.Messages
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

/**
 * Performs the laptop's clicks, swipes, Back/Home/Recents and typing on this phone. Android only allows this through
 * an accessibility service, which the user must switch on in system settings. It receives no accessibility events and
 * reads nothing except the focused text field (to insert typed text). It acts only when [RemoteControl] allows it:
 * casting to the paired laptop with "Let the laptop control this phone" switched on.
 */
class ControlService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = WeakReference(this)
        _enabled.value = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        _enabled.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        _enabled.value = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    fun perform(stroke: Stroke): Boolean {
        val (w, h) = screenSize()
        val path = Path()
        stroke.points.forEachIndexed { i, p ->
            val (x, y) = GestureBuilder.toPixels(p, w, h)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val duration = stroke.durationMs.coerceIn(1L, GestureDescription.getMaxGestureDuration())
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun global(code: Int): Boolean = performGlobalAction(
        when (code) {
            Messages.GLOBAL_BACK -> GLOBAL_ACTION_BACK
            Messages.GLOBAL_HOME -> GLOBAL_ACTION_HOME
            Messages.GLOBAL_RECENTS -> GLOBAL_ACTION_RECENTS
            else -> GLOBAL_ACTION_NOTIFICATIONS
        },
    )

    /** The focused, editable, non-password text field, if any. */
    private fun focusedField(): AccessibilityNodeInfo? {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        // Password fields only expose masked text, so editing them here would corrupt the password.
        return node.takeIf { it.isEditable && !it.isPassword }
    }

    private fun currentText(node: AccessibilityNodeInfo): String =
        if (node.isShowingHintText) "" else node.text?.toString().orEmpty()

    private fun apply(node: AccessibilityNodeInfo, r: TextEdit.Result) {
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, r.text) },
        )
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, r.cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, r.cursor)
            },
        )
    }

    fun type(text: String) {
        val node = focusedField() ?: return
        val cur = currentText(node)
        apply(node, TextEdit.insert(cur, node.textSelectionStart, node.textSelectionEnd, text))
    }

    fun key(code: Int) {
        val node = focusedField() ?: return
        when (code) {
            Messages.KEY_BACKSPACE -> {
                val cur = currentText(node)
                apply(node, TextEdit.backspace(cur, node.textSelectionStart, node.textSelectionEnd))
            }
            Messages.KEY_ENTER -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id }
                ) {
                    node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                } else if (node.isMultiLine) {
                    type("\n")
                }
            }
        }
    }

    companion object {
        @Volatile private var instance: WeakReference<ControlService>? = null
        private val _enabled = MutableStateFlow(false)
        /** True while the user has the accessibility service switched on. */
        val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
        fun current(): ControlService? = instance?.get()
    }
}

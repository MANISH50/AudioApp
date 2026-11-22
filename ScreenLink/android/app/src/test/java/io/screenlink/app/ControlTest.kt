package io.screenlink.app

import io.screenlink.app.cast.ScreenCastService
import io.screenlink.app.control.GestureBuilder
import io.screenlink.app.control.TextEdit
import io.screenlink.app.net.FrameWriter
import io.screenlink.app.net.Messages
import io.screenlink.app.net.Protocol
import io.screenlink.app.net.ProtocolException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class ControlProtocolTest {
    @Test fun pointer_layout_matches_windows() {
        // Same bytes as ControlTests.Pointer_layout_matches_android on Windows.
        val out = ByteArrayOutputStream()
        FrameWriter(out).apply { write(Protocol.CONTROL_POINTER, Messages.pointer(Messages.POINTER_UP, 0x8000, 0xFFFF)); flush() }
        assertArrayEquals(byteArrayOf(0x40, 0, 0, 0, 5, 2, 0x80.toByte(), 0, 0xFF.toByte(), 0xFF.toByte()), out.toByteArray())
        val p = Messages.parsePointer(Messages.pointer(Messages.POINTER_DOWN, 65535, 1))
        assertEquals(65535, p.x)
        assertEquals(1, p.y)
    }

    @Test fun scroll_is_signed() {
        val s = Messages.parseScroll(Messages.scroll(10, 20, -240))
        assertEquals(-240, s.delta)
    }

    @Test(expected = ProtocolException::class) fun bad_pointer_action() { Messages.parsePointer(byteArrayOf(9, 0, 0, 0, 0)) }
    @Test(expected = ProtocolException::class) fun unknown_global_code() { Messages.parseCode(byteArrayOf(9), Messages.GLOBAL_NOTIFICATIONS) }
    @Test(expected = ProtocolException::class) fun invalid_utf8_text() { Messages.parseText(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) }
}

class GestureBuilderTest {
    private var now = 0L
    private val g = GestureBuilder { now }

    @Test fun click_is_a_tap() {
        g.onPointer(Messages.POINTER_DOWN, 32768, 32768)
        now += 80
        val s = g.onPointer(Messages.POINTER_UP, 32800, 32768)!!
        assertEquals(1, s.points.size)
        assertEquals(GestureBuilder.TAP_MS, s.durationMs)
        assertEquals(0.5f, s.points[0].x, 0.01f)
    }

    @Test fun holding_still_is_a_long_press() {
        g.onPointer(Messages.POINTER_DOWN, 1000, 1000)
        now += 900
        val s = g.onPointer(Messages.POINTER_UP, 1000, 1000)!!
        assertEquals(1, s.points.size)
        assertEquals(900, s.durationMs)
    }

    @Test fun moving_is_a_swipe_with_real_timing() {
        g.onPointer(Messages.POINTER_DOWN, 32768, 50000)
        now += 100; g.onPointer(Messages.POINTER_MOVE, 32768, 40000)
        now += 100; g.onPointer(Messages.POINTER_MOVE, 32768, 30000)
        now += 100
        val s = g.onPointer(Messages.POINTER_UP, 32768, 20000)!!
        assertEquals(4, s.points.size)
        assertEquals(300, s.durationMs)
    }

    @Test fun up_without_down_and_cancel_do_nothing() {
        assertNull(g.onPointer(Messages.POINTER_UP, 1, 1))
        g.onPointer(Messages.POINTER_DOWN, 1, 1)
        g.onPointer(Messages.POINTER_CANCEL, 1, 1)
        assertNull(g.onPointer(Messages.POINTER_UP, 1, 1))
    }

    @Test fun very_long_drags_are_thinned_not_unbounded() {
        g.onPointer(Messages.POINTER_DOWN, 0, 0)
        repeat(5000) { g.onPointer(Messages.POINTER_MOVE, it * 10, it * 10) }
        val s = g.onPointer(Messages.POINTER_UP, 60000, 60000)!!
        assertTrue(s.points.size <= GestureBuilder.MAX_POINTS + 1)
    }

    @Test fun wheel_up_moves_finger_down() {
        val s = GestureBuilder.scroll(32768, 32768, 120)!!
        assertTrue(s.points[1].y > s.points[0].y)
        assertTrue(GestureBuilder.scroll(32768, 32768, -120)!!.points[1].y < 0.5f)
        assertNull(GestureBuilder.scroll(1, 1, 0))
    }

    @Test fun pixels_stay_on_screen() {
        val (x, y) = GestureBuilder.toPixels(io.screenlink.app.control.Pt(1f, 1f), 1080, 2400)
        assertEquals(1079f, x)
        assertEquals(2399f, y)
    }
}

class TextEditTest {
    @Test fun insert_at_cursor() = assertEquals(TextEdit.Result("heXllo", 3), TextEdit.insert("hello", 2, 2, "X"))
    @Test fun insert_replaces_selection() = assertEquals(TextEdit.Result("hXo", 2), TextEdit.insert("hello", 1, 4, "X"))
    @Test fun unknown_cursor_appends() = assertEquals(TextEdit.Result("hi!", 3), TextEdit.insert("hi", -1, -1, "!"))
    @Test fun backspace_deletes_one_character() = assertEquals(TextEdit.Result("hllo", 1), TextEdit.backspace("hello", 2, 2))
    @Test fun backspace_deletes_whole_emoji() = assertEquals(TextEdit.Result("a", 1), TextEdit.backspace("a👋", 3, 3))
    @Test fun backspace_at_start_does_nothing() = assertEquals(TextEdit.Result("ab", 0), TextEdit.backspace("ab", 0, 0))
}

class WholeScreenTest {
    @Test fun same_size_any_orientation() {
        assertTrue(ScreenCastService.sameSize(1080, 2400, 1080, 2400))
        assertTrue(ScreenCastService.sameSize(2400, 1080, 1080, 2400))
        assertFalse(ScreenCastService.sameSize(1080, 1800, 1080, 2400))
        assertNotNull(ScreenCastService.wholeScreen)
    }
}

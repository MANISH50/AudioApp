package io.screenlink.app

import io.screenlink.app.cast.VideoSize
import io.screenlink.app.data.PairedLaptop
import io.screenlink.app.data.PairingStore
import io.screenlink.app.keyboard.TextDiff
import io.screenlink.app.net.Discovery
import io.screenlink.app.net.FrameReader
import io.screenlink.app.net.FrameWriter
import io.screenlink.app.net.Keys
import io.screenlink.app.net.Messages
import io.screenlink.app.net.OutboundQueue
import io.screenlink.app.net.Pairing
import io.screenlink.app.net.Protocol
import io.screenlink.app.net.ProtocolException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.nio.ByteBuffer

class ProtocolTest {
    @Test fun frames_round_trip() {
        val out = ByteArrayOutputStream()
        val w = FrameWriter(out)
        w.write(Protocol.HELLO, Messages.hello("Pixel ✓"))
        w.write(Protocol.KEY_TEXT, Messages.keyText("héllo 👋"))
        w.write(Protocol.REQUEST_KEYFRAME)
        w.flush()
        val r = FrameReader(ByteArrayInputStream(out.toByteArray()))
        val hello = r.read()
        assertEquals(Protocol.HELLO, hello.type)
        val h = Messages.parseHello(hello.payload)
        assertEquals(Protocol.VERSION, h.version)
        assertEquals("Pixel ✓", h.name)
        assertEquals("héllo 👋", String(r.read().payload, Charsets.UTF_8))
        assertEquals(0, r.read().payload.size)
        try { r.read(); throw AssertionError("expected EOF") } catch (_: EOFException) { }
    }

    @Test fun wire_format_is_type_length_payload() {
        val out = ByteArrayOutputStream()
        FrameWriter(out).apply { write(0x21, Messages.keyPress(Keys.ENTER, Protocol.MOD_CTRL)); flush() }
        assertArrayEquals(byteArrayOf(0x21, 0, 0, 0, 3, 0, 0x0D, 1), out.toByteArray())
    }

    @Test(expected = ProtocolException::class) fun oversize_frame_rejected() {
        val bytes = ByteBuffer.allocate(5).put(Protocol.VIDEO_FRAME.toByte()).putInt(Protocol.MAX_PAYLOAD + 1).array()
        FrameReader(ByteArrayInputStream(bytes)).read()
    }

    @Test(expected = ProtocolException::class) fun bad_hello_rejected() {
        Messages.parseHello(byteArrayOf(0, 1, 0x7F, 0x7F))
    }

    @Test fun video_frame_layout() {
        val p = Messages.videoFrame(1234L, true, byteArrayOf(9, 8, 7))
        val b = ByteBuffer.wrap(p)
        assertEquals(1234L, b.long)
        assertEquals(1, b.get().toInt())
        assertEquals(3, b.remaining())
    }

    @Test fun truncate_never_splits_characters() {
        assertEquals("ab", Messages.truncateUtf8("ab👋", 5))
        assertEquals("ab👋", Messages.truncateUtf8("ab👋", 6))
    }

    @Test fun allowed_keys() {
        assertTrue(Keys.ENTER in Keys.ALLOWED)
        assertTrue('Z'.code in Keys.ALLOWED)
        assertTrue(Keys.F1 + 11 in Keys.ALLOWED)
        assertFalse(0x5B in Keys.ALLOWED) // the Windows key itself is only a modifier
    }
}

class PairingTest {
    @Test fun code_matches_shared_test_vector() {
        // Same vector in ScreenLink/windows/tests/ScreenLink.Core.Tests/PairingTests.cs
        val laptop = ByteArray(32) { it.toByte() }
        val phone = ByteArray(32) { (it + 32).toByte() }
        assertEquals("337226", Pairing.code(laptop, phone))
    }

    @Test fun code_depends_on_order_and_both_keys() {
        val a = ByteArray(32) { 1 }
        val b = ByteArray(32) { 2 }
        assertTrue(Pairing.code(a, b) != Pairing.code(b, a))
        assertEquals(6, Pairing.code(a, b).length)
    }

    @Test fun hex_round_trip() {
        val b = ByteArray(32) { (it * 7).toByte() }
        assertArrayEquals(b, Pairing.fromHex(Pairing.toHex(b)))
        assertNull(Pairing.fromHex("zz"))
    }
}

class TextDiffTest {
    @Test fun typing_appends() = assertEquals(TextDiff.Edit(0, "o"), TextDiff.diff("hell", "hello"))
    @Test fun backspace() = assertEquals(TextDiff.Edit(1, ""), TextDiff.diff("hello", "hell"))
    @Test fun autocorrect_replaces_word() = assertEquals(TextDiff.Edit(2, "he "), TextDiff.diff("say teh", "say the "))
    @Test fun emoji_counts_as_one_backspace() = assertEquals(TextDiff.Edit(1, ""), TextDiff.diff("hi👋", "hi"))
    @Test fun emoji_swap_keeps_pairs_whole() {
        val e = TextDiff.diff("a😀", "a😃")
        assertEquals(1, e.backspaces)
        assertEquals("😃", e.insert)
    }
}

class VideoSizeTest {
    @Test fun portrait_phone_scaled_to_720p_class() {
        val s = VideoSize.fit(1080, 2400, 1280)
        assertEquals(1280, s.height)
        assertEquals(576, s.width)
    }

    @Test fun never_upscales_and_multiple_of_16() {
        val s = VideoSize.fit(700, 500, 1920)
        assertEquals(0, s.width % 16)
        assertEquals(0, s.height % 16)
        assertTrue(s.width <= 700 && s.height <= 500)
    }
}

class DiscoveryTest {
    private val fp = "ab".repeat(32)

    @Test fun parses_valid_reply() {
        val bytes = "SCREENLINK!1\n47420\n$fp\nMy Laptop".toByteArray()
        val d = Discovery.parseReply("192.168.1.5", bytes, bytes.size)
        assertNotNull(d)
        assertEquals(47420, d!!.port)
        assertEquals("My Laptop", d.name)
    }

    @Test fun rejects_bad_replies() {
        for (s in listOf("HELLO", "SCREENLINK!1\n80\n$fp\nx", "SCREENLINK!1\n47420\nnothex\nx", "SCREENLINK!1\n47420")) {
            val b = s.toByteArray()
            assertNull(s, Discovery.parseReply("1.2.3.4", b, b.size))
        }
    }
}

class OutboundQueueTest {
    @Test fun control_goes_before_video() {
        val q = OutboundQueue()
        q.offerVideoConfig(byteArrayOf(1))
        q.offerControl(Protocol.KEY_TEXT, byteArrayOf(2))
        assertEquals(Protocol.KEY_TEXT, q.take(10)!!.type)
        assertEquals(Protocol.VIDEO_CONFIG, q.take(10)!!.type)
        assertNull(q.take(10))
    }

    @Test fun video_is_bounded_and_config_flushes_stale_frames() {
        val q = OutboundQueue(videoCapacity = 2)
        assertTrue(q.offerVideoFrame(byteArrayOf()))
        assertTrue(q.offerVideoFrame(byteArrayOf()))
        assertFalse(q.offerVideoFrame(byteArrayOf()))
        q.offerVideoConfig(byteArrayOf(5))
        assertEquals(Protocol.VIDEO_CONFIG, q.take(10)!!.type)
        assertNull(q.take(10))
    }

    @Test fun closed_queue_refuses() {
        val q = OutboundQueue()
        q.close()
        assertFalse(q.offerControl(Protocol.PING))
        assertNull(q.take(10))
    }
}

class PairingStoreTest {
    @Test fun encode_decode_strips_separators() {
        val list = listOf(PairedLaptop("cd".repeat(32), "10.0.0.2", 47420, "Office\tPC\nX"))
        val back = PairingStore.decode(PairingStore.encode(list))
        assertEquals(1, back.size)
        assertEquals("OfficePCX", back[0].name)
        assertEquals(47420, back[0].port)
    }
}

package io.spatia.app.player

import android.content.Context
import io.spatia.dsp.io.WavWriter
import io.spatia.dsp.sample.SampleTrack
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Renders Spatia's synthesised sample track once into the app's private cache and reuses it. */
object SampleTrackFile {
    private const val NAME = "sample_v1.wav" // bump when SampleTrack changes
    private const val RATE = 44100

    suspend fun get(context: Context): File = withContext(Dispatchers.Default) {
        val file = File(context.cacheDir, NAME)
        val expected = 44L + (SampleTrack.LENGTH_S * RATE).toLong() * 4
        if (!file.exists() || file.length() != expected) {
            val tmp = File(context.cacheDir, "$NAME.tmp")
            tmp.writeBytes(WavWriter.write16(SampleTrack.render(RATE), RATE))
            if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
        }
        file
    }
}

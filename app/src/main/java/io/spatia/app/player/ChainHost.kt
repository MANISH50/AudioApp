package io.spatia.app.player

import io.spatia.dsp.DspChain
import io.spatia.dsp.analysis.Meters

/** What [SpatiaAudioProcessor] needs from the rest of the app; an interface so the processor can be tested on the JVM. */
interface ChainHost {
    val bitPerfect: Boolean
    var meters: Meters?
    fun register(chain: DspChain)
    fun unregister(chain: DspChain)
}

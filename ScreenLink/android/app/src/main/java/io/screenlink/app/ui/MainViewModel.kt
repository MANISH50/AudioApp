package io.screenlink.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.screenlink.app.ScreenLinkApp
import io.screenlink.app.cast.Quality
import io.screenlink.app.cast.ScreenCastService
import io.screenlink.app.data.PairedLaptop
import io.screenlink.app.keyboard.TextDiff
import io.screenlink.app.net.Discovery
import io.screenlink.app.net.DiscoveredLaptop
import io.screenlink.app.net.Keys
import io.screenlink.app.net.LinkState
import io.screenlink.app.net.LinkTarget
import io.screenlink.app.net.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ScreenLinkApp
    val link: StateFlow<LinkState> = app.link.state
    val paired: StateFlow<List<PairedLaptop>> = app.pairings.laptops.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val casting: StateFlow<Boolean> = ScreenCastService.casting
    val castError: StateFlow<Boolean> = ScreenCastService.lastError

    private val _found = MutableStateFlow<List<DiscoveredLaptop>>(emptyList())
    val found: StateFlow<List<DiscoveredLaptop>> = _found.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private val _quality = MutableStateFlow(Quality.STANDARD)
    val quality: StateFlow<Quality> = _quality.asStateFlow()
    private val _modifiers = MutableStateFlow(0)
    val modifiers: StateFlow<Int> = _modifiers.asStateFlow()

    fun scan() {
        if (_scanning.value) return
        _scanning.value = true
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { runCatching { Discovery.scan(getApplication()) }.getOrDefault(emptyList()) }
            _found.value = list
            _scanning.value = false
        }
    }

    fun connect(d: DiscoveredLaptop) {
        // The discovery reply is only a hint: a laptop counts as paired only if we pinned this exact fingerprint before.
        val pin = paired.value.firstOrNull { it.fingerprintHex == d.fingerprintHex }?.fingerprintHex
        app.link.connect(LinkTarget(d.host, d.port, d.name, pin))
    }

    fun connect(p: PairedLaptop) = app.link.connect(LinkTarget(p.host, p.port, p.name, p.fingerprintHex))

    /** Manual address entry ("192.168.1.20" or "192.168.1.20:47420"). Returns false if it isn't a valid address. */
    fun connectManual(input: String): Boolean {
        val t = input.trim()
        val host = t.substringBefore(':')
        val port = if (':' in t) t.substringAfter(':').toIntOrNull() ?: return false else Protocol.TCP_PORT
        if (port !in 1024..65535) return false
        if (!HOST.matches(host)) return false
        app.link.connect(LinkTarget(host, port, host, null))
        return true
    }

    fun disconnect() {
        ScreenCastService.stop(getApplication())
        app.link.disconnect()
    }

    fun confirmCode(match: Boolean) = app.link.confirmCode(match)

    fun forget(p: PairedLaptop) = viewModelScope.launch { app.pairings.remove(p.fingerprintHex) }

    fun setQuality(q: Quality) { _quality.value = q }
    fun clearCastError() = ScreenCastService.clearError()

    // Keyboard

    fun toggleModifier(m: Int) { _modifiers.value = _modifiers.value xor m }

    fun key(vk: Int) {
        app.link.sendKey(vk, _modifiers.value)
        _modifiers.value = 0
    }

    fun shortcut(vk: Int, mods: Int) { app.link.sendKey(vk, mods) }

    /** Called with every change of the keyboard text field. */
    fun typed(old: String, new: String) {
        val e = TextDiff.diff(old, new)
        val mods = _modifiers.value
        if (mods != 0 && e.backspaces == 0 && e.insert.length == 1 && e.insert[0].isLetterOrDigit() && e.insert[0].code < 128) {
            // Ctrl/Alt/Win + a letter is a shortcut, not text.
            app.link.sendKey(e.insert[0].uppercaseChar().code, mods)
            _modifiers.value = 0
            return
        }
        repeat(e.backspaces) { app.link.sendKey(Keys.BACKSPACE, 0) }
        app.link.sendText(e.insert)
    }

    private companion object {
        val HOST = Regex("^(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)$|^[A-Za-z0-9](?:[A-Za-z0-9-]{0,62})(?:\\.[A-Za-z0-9-]{1,63})*$")
    }
}

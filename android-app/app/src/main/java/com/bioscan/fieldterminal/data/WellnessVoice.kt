package com.bioscan.fieldterminal.data

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import org.json.JSONObject
import java.util.Locale

// The voice of a wellness session. A cue with a recording (assets/wellness/audio/<cue id>.mp3, made from the script with
// scripts/wellness-audio/generate.mjs) is played; a cue without one is read by the phone's own speech engine, so the
// session works before any recording exists. The words are in assets/wellness/<script version>.json.
class WellnessVoice(private val context: Context, scriptVersion: String) {
    private val cues: Map<String, String> = runCatching {
        val json = JSONObject(context.assets.open("wellness/$scriptVersion.json").bufferedReader().use { it.readText() })
        val c = json.getJSONObject("cues")
        c.keys().asSequence().associateWith { c.getString(it) }
    }.getOrDefault(emptyMap())

    private var ready = false
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
            tts?.setSpeechRate(0.9f)
            ready = true
        }
    }
    private var player: MediaPlayer? = null

    /** The words of a cue, for showing on screen. */
    fun text(id: String): String = cues[id].orEmpty()

    /** Says a cue now, cutting off whatever is being said. */
    fun say(id: String) {
        stop()
        val recorded = runCatching { context.assets.openFd("wellness/audio/$id.mp3") }.getOrNull()
        if (recorded != null) {
            runCatching {
                player = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setUsage(AudioAttributes.USAGE_MEDIA).build())
                    setDataSource(recorded.fileDescriptor, recorded.startOffset, recorded.length)
                    setOnCompletionListener { it.release(); if (player === it) player = null }
                    prepare()
                    start()
                }
            }
            runCatching { recorded.close() }
            return
        }
        val words = cues[id] ?: return
        if (ready) tts?.speak(words, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun stop() {
        runCatching { player?.release() }
        player = null
        tts?.stop()
    }

    fun release() {
        stop()
        tts?.shutdown()
        tts = null
    }
}

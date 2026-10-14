package com.beatpad.audio

import java.util.concurrent.atomic.AtomicInteger

/** A note on a note track. start and len are in 16th-note steps. */
data class SeqNote(val id: Int, val start: Int, val len: Int, val midi: Int) {
    companion object {
        private val ids = AtomicInteger(0)
        fun newId(): Int = ids.incrementAndGet()
    }
}

/**
 * A melodic track: notes with pitch and length, played by an instrument
 * or by a pad's sample at different pitches. The notes list is replaced, never mutated,
 * so the audio thread always sees a consistent copy.
 */
class NoteTrack {
    @Volatile var instrument: Instrument? = Instrument.KEYS   // null = use a pad's sample
    @Volatile var padSource = 0
    @Volatile var volume = 0.7f
    @Volatile var bars = 1
    @Volatile var muted = false
    @Volatile var notes: List<SeqNote> = emptyList()

    val steps: Int get() = bars * 16

    companion object {
        const val MIN_NOTE = 24   // C1
        const val MAX_NOTE = 96   // C7
        const val SAMPLE_ROOT = 60 // a pad sample plays at its own pitch on C4
    }
}

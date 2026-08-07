package com.mouya.musichaptics

import kotlin.math.roundToInt

/**
 * Single semantic haptic timeline.
 *
 * Detectors only publish [HapticCommand]s; this class is the sole place where
 * semantic primitives are arbitrated and authored into a future waveform.
 * It intentionally does not call Vibrator APIs.
 */
class HapticTimelineScheduler(
    private val windowMs: Long = 100L,
    private val binMs: Long = 10L,
    // v3.11: LRA waveform smoother — dynamically adapted per device Q factor.
    // High-Q actuators (0816 ESA Q=18, 0815 Q=15) need stronger smoothing
    // because their narrow resonance band amplifies inter-bin transients.
    // Lower-Q actuators (CSA0916 Q=12) can use lighter smoothing to
    // preserve more texture detail.
    private var maxSlewPerBin: Int = 35,   // max Δ amplitude between adjacent 10ms bins
    private var smootherAlpha: Float = 0.38f // one-pole LPF coefficient (0=fully smooth, 1=no filter)
) {

    /**
     * v3.11: Adapt smoothing parameters to the device's actuator Q factor.
     * Higher Q → more aggressive smoothing needed.
     * Q < 13:  maxSlew=45, alpha=0.50 (light smoothing, preserve texture)
     * Q 13-16: maxSlew=35, alpha=0.38 (moderate smoothing, balanced)
     * Q > 16:  maxSlew=25, alpha=0.25 (heavy smoothing, kill 跳跳糖)
     */
    fun adaptToActuatorQ(qFactor: Float) {
        when {
            qFactor > 16f -> {
                // 0816 ESA / OnePlus 15
                // High Q needs heavy smoothing to avoid pop-rocks. 
                // Smaller alpha = heavier smoothing (more prev value kept)
                maxSlewPerBin = 40
                smootherAlpha = 0.20f 
            }
            qFactor > 12f -> {
                maxSlewPerBin = 60
                smootherAlpha = 0.35f
            }
            else -> {
                maxSlewPerBin = 85
                smootherAlpha = 0.50f
            }
        }
    }
    private data class Event(
        val primitive: HapticPrimitive,
        val timestampMs: Long,
        val priority: Int
    )

    private val lock = Any()
    private val pending = ArrayList<Event>()

    /** Cross-window continuity: the last bin value of the previous render() call. */
    private var prevWindowTail: Int = 0

    fun offer(command: HapticCommand) {
        command.primitive?.let { offerPrimitive(it, command.timestamp) }
    }

    /** Used by the explicitly labelled low-band-onset fallback track. */
    fun offerPrimitive(primitive: HapticPrimitive, timestampMs: Long) {
        synchronized(lock) {
            pending += Event(primitive, timestampMs, priorityOf(primitive))
            // A live engine must not accumulate events while the renderer is stalled.
            if (pending.size > 96) {
                pending.sortByDescending { it.priority }
                pending.subList(64, pending.size).clear()
            }
        }
    }

    fun render(
        nativeSamples: FloatArray,
        sampleCount: Int,
        windowStartMs: Long,
        structure: MusicStructureAnalyzer.Snapshot,
        outputGain: Float
    ): IntArray {
        val bins = (windowMs / binMs).toInt()
        val base = IntArray(bins) { index ->
            val source = if (sampleCount > 0) nativeSamples[index.coerceAtMost(sampleCount - 1)] else 0f
            (source * sectionBodyGain(structure.section)).roundToInt().coerceIn(0, 255)
        }
        val events = synchronized(lock) {
            val expiry = windowStartMs - 40L
            pending.removeAll { it.timestampMs < expiry }
            val selected = pending.filter { it.timestampMs < windowStartMs + windowMs }
                .sortedByDescending { it.priority }
            pending.removeAll(selected.toSet())
            selected
        }

        // Higher priority events author first; lower-priority layers may fill space
        // but cannot overwrite an already stronger accent in that bin.
        for (event in events) {
            val start = ((event.timestampMs - windowStartMs) / binMs).toInt().coerceIn(0, bins - 1)
            mixPrimitive(base, start, event.primitive, structure)
        }

        // ── v3.10.19: LRA Waveform Smoother ──
        // On high-Q X-axis LRAs (OnePlus 15: Q=16, f0=200Hz, 3ms rise), abrupt
        // inter-bin amplitude jumps produce a series of discrete mechanical
        // transients — perceived as "pop rocks" (跳跳糖) buzzing.
        //
        // Two-stage smoothing:
        // 1. Slew-rate limiter: cap |Δamp| between adjacent bins to maxSlewPerBin.
        //    This prevents the LRA from being step-driven.  The LRA's mechanical
        //    rise time (3ms) is close to the bin duration (10ms), so a 40-count
        //    slew limit gives ~4ms effective ramp — matching the actuator's
        //    physical response envelope.
        // 2. One-pole low-pass: smootherAlpha blends each bin with its predecessor.
        //    0.45 = gentle smoothing that preserves beat attacks but removes
        //    sample-level jitter from the C++ synthesizer output.
        val raw = IntArray(bins) { (base[it] * outputGain).roundToInt().coerceIn(0, 255) }
        val finalOutput = IntArray(bins)
        var prev = prevWindowTail.toFloat()
        for (i in 0 until bins) {
            val target = raw[i].toFloat()
            
            // Asymmetric Slew rate limiting
            // Fast rise (for kick), slow decay (anti-pop-rocks)
            val diff = target - prev
            val slewedTarget = if (diff > maxSlewPerBin * 1.5f) { 
                prev + (maxSlewPerBin * 1.5f) // Allow 1.5x speed on attack
            } else if (diff < -maxSlewPerBin) {
                prev - maxSlewPerBin
            } else {
                target
            }
            
            // Asymmetric smoothing
            // Alpha means how much TARGET we accept (smaller = smoother).
            // We want very little smoothing on attack to keep the punch.
            val currentAlpha = if (slewedTarget > prev) {
                smootherAlpha * 2f // Double alpha = 2x faster response on attack
            } else {
                smootherAlpha      // Heavy smoothing on decay
            }.coerceIn(0f, 1f)

            prev = (prev * (1f - currentAlpha)) + (slewedTarget * currentAlpha)
            finalOutput[i] = prev.roundToInt().coerceIn(0, 255)
        }
        prevWindowTail = finalOutput.lastOrNull() ?: 0
        return finalOutput
    }

    private fun mixPrimitive(out: IntArray, start: Int, primitive: HapticPrimitive, structure: MusicStructureAnalyzer.Snapshot) {
        fun put(index: Int, value: Int) {
            if (index in out.indices) out[index] = maxOf(out[index], value.coerceIn(0, 255))
        }
        // v3.10.19: Interpolated envelope write — instead of writing discrete
        // values to sparse bins (which causes step jumps on high-Q LRAs),
        // linearly interpolate between envelope points so the LRA sees a
        // continuous drive curve.  This is critical for OnePlus 15 (Q=16):
        // a jump from 255→112 between adjacent 10ms bins produces a mechanical
        // transient that sounds like "click click click" (跳跳糖).
        fun putInterpolated(startIdx: Int, envelope: FloatArray, intensity: Int) {
            for (i in envelope.indices) {
                val targetIdx = startIdx + i
                if (targetIdx !in out.indices) break
                put(targetIdx, (intensity * envelope[i]).roundToInt())
                // Interpolate to next point
                if (i < envelope.lastIndex) {
                    val nextIdx = startIdx + i + 1
                    if (nextIdx !in out.indices) break
                    val avg = (envelope[i] + envelope[i + 1]) * 0.5f
                    put(nextIdx, (intensity * avg).roundToInt())
                }
            }
        }
        when (primitive) {
            is HapticPrimitive.Impact -> {
                val env = when {
                    primitive.semantic.contains("SNARE") || primitive.semantic.contains("PLUCKED") ->
                        // v3.10.19: Smoother double-stage strike — fill intermediate bins
                        floatArrayOf(1f, .85f, .55f, .72f, .45f, .25f, .18f, .08f, .04f, .02f)
                    primitive.semantic.contains("KICK") || primitive.semantic.contains("SUB_") ||
                        primitive.semantic == "LOW_BAND_ONSET" || primitive.semantic == "PCM_LOW_BAND_ATTACK" ->
                        // v3.10.19: Smoother dense low-band attack — gradual decay curve
                        floatArrayOf(1f, .92f, .80f, .65f, .50f, .38f, .28f, .20f, .13f, .08f)
                    else ->
                        // v3.10.19: Extended generic impact with smooth decay
                        floatArrayOf(1f, .80f, .60f, .42f, .28f, .18f, .12f, .07f, .04f, .02f)
                }
                putInterpolated(start, env, primitive.intensity)
            }
            is HapticPrimitive.Pulse -> {
                val hits = primitive.repeatCount.coerceIn(1, 3)
                val step = (primitive.periodMs / binMs).toInt().coerceAtLeast(2)
                repeat(hits) { hit ->
                    val hitStart = start + hit * step
                    put(hitStart, (primitive.intensity * .76f).roundToInt())
                    // v3.10.19: Add decay tail after each pulse hit
                    if (hitStart + 1 < out.size) put(hitStart + 1, (primitive.intensity * .45f).roundToInt())
                    if (hitStart + 2 < out.size) put(hitStart + 2, (primitive.intensity * .20f).roundToInt())
                }
            }
            is HapticPrimitive.Texture -> {
                // v3.10.19: Dense grains with smooth decay instead of sparse clicks.
                val bins = (primitive.durationMs / binMs).toInt().coerceIn(1, 5)
                repeat(bins) { i ->
                    val decay = 1f - i * 0.15f
                    put(start + i, (primitive.intensity * .52f * decay).roundToInt())
                }
            }
            is HapticPrimitive.Wave -> {
                val durationBins = (primitive.durationMs / binMs).toInt().coerceIn(1, out.size - start)
                repeat(durationBins) { i ->
                    val curveIndex = (i * primitive.amplitudeCurve.size / durationBins)
                        .coerceIn(0, primitive.amplitudeCurve.lastIndex)
                    val sectionGain = if (structure.section == MusicStructureAnalyzer.Section.BREAKDOWN) .75f else 1f
                    put(start + i, (primitive.amplitudeCurve[curveIndex] * 255f * sectionGain).roundToInt())
                }
            }
        }
    }

    private fun priorityOf(primitive: HapticPrimitive): Int = when (primitive) {
        is HapticPrimitive.Impact -> when {
            primitive.semantic.contains("KICK") || primitive.semantic.contains("SUB_") ||
                primitive.semantic == "LOW_BAND_ONSET" || primitive.semantic == "PCM_LOW_BAND_ATTACK" -> 100
            primitive.semantic.contains("SNARE") || primitive.semantic.contains("PLUCKED") -> 80
            else -> 70
        }
        is HapticPrimitive.Pulse -> 75
        is HapticPrimitive.Wave -> when (primitive.semantic) {
            "VOCAL_PHRASE", "VOCAL_SUSTAIN" -> 60
            "BASS_SUSTAIN" -> 45
            else -> 50
        }
        is HapticPrimitive.Texture -> 20
    }

    private fun sectionBodyGain(section: MusicStructureAnalyzer.Section): Float = when (section) {
        MusicStructureAnalyzer.Section.INTRO -> .68f
        MusicStructureAnalyzer.Section.VERSE -> .82f
        MusicStructureAnalyzer.Section.BUILD -> .96f
        MusicStructureAnalyzer.Section.CHORUS -> 1f
        MusicStructureAnalyzer.Section.BREAKDOWN -> .58f
        MusicStructureAnalyzer.Section.OUTRO -> .55f
    }

    // ════════════════════════════════════════════════════════════════
    //  v3.8 Multi-Track Timeline: Independent track rendering + sidechain
    // ════════════════════════════════════════════════════════════════
    data class SemanticTrack(
        val name: String,
        val envelope: FloatArray = FloatArray(10) { 0f },
        var active: Boolean = false,
        var priority: Int = 0
    )

    private val tracks = mutableMapOf(
        "KICK" to SemanticTrack("Kick"),
        "SNARE" to SemanticTrack("Snare"),
        "VOCAL" to SemanticTrack("Vocal"),
        "BODY" to SemanticTrack("Body")
    )

    fun applyMultiTrackFrames(frames: FloatArray, count: Int) {
        val maxFramesPerPull = 10
        for (i in 0 until minOf(count, maxFramesPerPull)) {
            val kick = frames.getOrElse(i * 4 + 0) { 0f }
            val snare = frames.getOrElse(i * 4 + 1) { 0f }
            val vocal = frames.getOrElse(i * 4 + 2) { 0f }
            val body = frames.getOrElse(i * 4 + 3) { 0f }
            tracks["KICK"]!!.envelope[i % 10] = kick
            tracks["SNARE"]!!.envelope[i % 10] = snare
            tracks["VOCAL"]!!.envelope[i % 10] = vocal
            tracks["BODY"]!!.envelope[i % 10] = body
            tracks["KICK"]!!.active = kick > 0.01f
            tracks["SNARE"]!!.active = snare > 0.01f
            tracks["VOCAL"]!!.active = vocal > 0.01f
            tracks["BODY"]!!.active = body > 0.01f
        }
    }

    fun composeSidechainCompressed(): IntArray {
        val bins = 10
        val result = IntArray(bins) { 0 }
        val kickVals = tracks["KICK"]!!.envelope
        val snareVals = tracks["SNARE"]!!.envelope
        val vocalVals = tracks["VOCAL"]!!.envelope
        val bodyVals = tracks["BODY"]!!.envelope
        for (i in 0 until bins) {
            val k = kickVals[i]
            val s = snareVals[i]
            val v = vocalVals[i]
            val b = bodyVals[i]
            val compressedBody = if (k > 0.3f) b * 0.4f else b
            val compressedVocal = if (s > 0.3f) v * 0.5f else v
            val composed = maxOf(
                k * 1.0f,
                s * 0.95f,
                compressedVocal * 0.85f,
                compressedBody * 0.75f
            )
            result[i] = (composed * 255f).roundToInt().coerceIn(0, 255)
        }
        return result
    }
}
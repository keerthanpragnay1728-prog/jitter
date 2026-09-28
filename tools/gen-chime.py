#!/usr/bin/env python3
"""Generate app/src/main/res/raw/jitter_chime.wav, the reminder chime.

    python3 tools/gen-chime.py

Deterministic: the same parameters write the same bytes, so the committed WAV
can be regenerated and diffed. Change a parameter here, rerun, and commit the
script and the WAV together; ChimeFileTest checks the WAV against these.

Parameters:
    sample rate   44100 Hz, mono, 16-bit signed PCM
    duration      280 ms (12348 samples)
    tone          E5, 659.25 Hz, with its octave at 0.15 of its amplitude,
                  which reads as a soft bell rather than a beep
    attack        8 ms raised-cosine fade in
    decay         exponential, time constant 110 ms
    release       40 ms raised-cosine fade out, reaching exactly zero
    peak          -12 dBFS, normalised after the envelope

Pure ASCII, standard library only.
"""
import math
import os
import struct
import wave

RATE = 44100
DURATION_MS = 280
FREQ_HZ = 659.25
OCTAVE_LEVEL = 0.15
ATTACK_MS = 8.0
DECAY_TAU_MS = 110.0
RELEASE_MS = 40.0
PEAK_DBFS = -12.0

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw", "jitter_chime.wav")


def envelope(t_ms):
    if t_ms <= 0.0 or t_ms >= DURATION_MS:
        return 0.0
    attack = 0.5 - 0.5 * math.cos(math.pi * t_ms / ATTACK_MS) if t_ms < ATTACK_MS else 1.0
    decay = math.exp(-max(0.0, t_ms - ATTACK_MS) / DECAY_TAU_MS)
    to_end = DURATION_MS - t_ms
    release = 0.5 - 0.5 * math.cos(math.pi * to_end / RELEASE_MS) if to_end < RELEASE_MS else 1.0
    return attack * decay * release


def samples():
    n = DURATION_MS * RATE // 1000
    raw = []
    for i in range(n):
        t = i / RATE
        tone = math.sin(2 * math.pi * FREQ_HZ * t) + OCTAVE_LEVEL * math.sin(2 * math.pi * 2 * FREQ_HZ * t)
        raw.append(envelope(t * 1000.0) * tone)
    peak = max(abs(v) for v in raw)
    target = (10 ** (PEAK_DBFS / 20.0)) * 32767
    return [int(round(v / peak * target)) for v in raw]


def main():
    pcm = samples()
    out = os.path.normpath(OUT)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with wave.open(out, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", v) for v in pcm))
    peak = max(abs(v) for v in pcm)
    print("wrote %s: %d samples, %.1f ms, peak %d (%.2f dBFS)" % (
        out, len(pcm), len(pcm) * 1000.0 / RATE, peak, 20 * math.log10(peak / 32767.0)))


if __name__ == "__main__":
    main()

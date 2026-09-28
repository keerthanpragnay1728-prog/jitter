#!/usr/bin/env python3
"""Generate app/src/main/res/raw/jitter_chime.wav, the reminder chime.

    python3 tools/gen-chime.py

Deterministic: the same parameters write the same bytes, so the committed WAV
can be regenerated and diffed. Change a parameter here, rerun, and commit the
script and the WAV together; ChimeFileTest checks the WAV against these.

Parameters:
    sample rate   44100 Hz, mono, 16-bit signed PCM
    duration      300 ms (13230 samples)
    tone          C5, 523.25 Hz, with two partials layered under it:
                  the fifth, G5 783.99 Hz, at 0.35 of its amplitude, and
                  the octave, C6 1046.50 Hz, at 0.20
    attack        12 ms raised-cosine fade in, shared by all three
    decay         exponential per partial, upper partials first: time
                  constants 160 ms (C5), 110 ms (G5), 80 ms (C6), which is
                  what makes it warm rather than thin: the tone darkens as it
                  fades instead of ringing bright to the end
    release       60 ms raised-cosine fade out, reaching exactly zero
    peak          -12 dBFS, normalised after the envelope

The previous version (E5 with one octave at 0.15, 280 ms, one 110 ms decay,
8 ms attack, 40 ms release) was clean on hardware and read as thin and
abrupt.

Pure ASCII, standard library only.
"""
import math
import os
import struct
import wave

RATE = 44100
DURATION_MS = 300
# (frequency Hz, relative amplitude, decay time constant ms)
PARTIALS = (
    (523.25, 1.00, 160.0),
    (783.99, 0.35, 110.0),
    (1046.50, 0.20, 80.0),
)
ATTACK_MS = 12.0
RELEASE_MS = 60.0
PEAK_DBFS = -12.0

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw", "jitter_chime.wav")


def shape(t_ms):
    """Attack and release, shared by every partial. Zero outside the note."""
    if t_ms <= 0.0 or t_ms >= DURATION_MS:
        return 0.0
    attack = 0.5 - 0.5 * math.cos(math.pi * t_ms / ATTACK_MS) if t_ms < ATTACK_MS else 1.0
    to_end = DURATION_MS - t_ms
    release = 0.5 - 0.5 * math.cos(math.pi * to_end / RELEASE_MS) if to_end < RELEASE_MS else 1.0
    return attack * release


def samples():
    n = DURATION_MS * RATE // 1000
    raw = []
    for i in range(n):
        t = i / RATE
        t_ms = t * 1000.0
        held = max(0.0, t_ms - ATTACK_MS)
        v = 0.0
        for freq, level, tau in PARTIALS:
            v += level * math.exp(-held / tau) * math.sin(2 * math.pi * freq * t)
        raw.append(shape(t_ms) * v)
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

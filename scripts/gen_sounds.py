#!/usr/bin/env python3
"""Original sound effects for Mycelium, synthesized from scratch (no samples, nothing downloaded).

Cozy chiptune-meets-foley: soft triangle/sine tones with gentle envelopes, shaped noise for rustles and puffs,
plucked strings, glassy bells, a small room reverb. Every sound is matched to one thing on screen:
creature hellos per kind, a voice per Snoop, shoo/poof/heal, hop, pick up/drop, moods, evolution, UI, ambience.
Writes app/src/main/assets/sounds/<name>.wav (mono, 16-bit, 32 kHz) and docs/SOUNDS.md.
Usage: python3 scripts/gen_sounds.py
"""
import math
import random
import struct
import wave
from array import array
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/main/assets/sounds"
SR = 32000
DOC = []


# ---------------------------------------------------------------- building blocks

def n(sec):
    return int(sec * SR)


def note(name):
    """'A4' -> Hz."""
    names = {"C": -9, "C#": -8, "D": -7, "D#": -6, "E": -5, "F": -4, "F#": -3, "G": -2, "G#": -1, "A": 0, "A#": 1, "B": 2}
    pitch, octave = name[:-1], int(name[-1])
    return 440.0 * 2 ** ((names[pitch] + (octave - 4) * 12) / 12)


def osc(kind, freq, dur, duty=0.5, vib=0.0, vib_rate=5.5, phase=0.0):
    """freq: Hz or a function t -> Hz. Band-friendly shapes (soft-edged square, triangle, sine)."""
    out = array("f", [0.0]) * n(dur)
    ph = phase
    for i in range(len(out)):
        t = i / SR
        f = freq(t) if callable(freq) else freq
        f *= 1 + vib * math.sin(2 * math.pi * vib_rate * t)
        ph = (ph + f / SR) % 1.0
        if kind == "sine":
            v = math.sin(2 * math.pi * ph)
        elif kind == "tri":
            v = 4 * abs(ph - 0.5) - 1
        elif kind == "square":   # slightly rounded so it never sounds harsh
            v = math.tanh(6 * (1 if ph < duty else -1) * abs(math.sin(math.pi * ph * 2)) ** 0.15)
        elif kind == "saw":
            v = 2 * ph - 1
        else:
            v = 0.0
        out[i] = v
    return out


def noise(dur, seed=1, color="white"):
    rng = random.Random(seed)
    out = array("f", [0.0]) * n(dur)
    b0 = b1 = b2 = 0.0
    for i in range(len(out)):
        w = rng.uniform(-1, 1)
        if color == "pink":   # Paul Kellet's economy pink filter
            b0 = 0.99765 * b0 + w * 0.0990460
            b1 = 0.96300 * b1 + w * 0.2965164
            b2 = 0.57000 * b2 + w * 1.0526913
            w = (b0 + b1 + b2 + w * 0.1848) * 0.18
        out[i] = w
    return out


def env(sig, a=0.005, d=0.05, s=0.6, r=0.1, hold=None):
    """ADSR over the signal's length (hold = sustain time, default: whatever is left)."""
    total = len(sig)
    na, nd, nr = n(a), n(d), n(r)
    ns = total - na - nd - nr if hold is None else n(hold)
    out = array("f", sig)
    for i in range(total):
        if i < na:
            g = i / max(1, na)
        elif i < na + nd:
            g = 1 - (1 - s) * (i - na) / max(1, nd)
        elif i < na + nd + max(0, ns):
            g = s
        else:
            k = i - na - nd - max(0, ns)
            g = s * max(0.0, 1 - k / max(1, nr))
        out[i] *= g
    return out


def expdecay(sig, tau):
    return array("f", (v * math.exp(-i / SR / tau) for i, v in enumerate(sig)))


def lowpass(sig, cutoff):
    """One-pole low-pass; cutoff may be a function t -> Hz (sweeps)."""
    out = array("f", sig)
    y = 0.0
    for i, v in enumerate(sig):
        c = cutoff(i / SR) if callable(cutoff) else cutoff
        a = 1 - math.exp(-2 * math.pi * max(20.0, c) / SR)
        y += a * (v - y)
        out[i] = y
    return out


def highpass(sig, cutoff):
    lp = lowpass(sig, cutoff)
    return array("f", (a - b for a, b in zip(sig, lp)))


def bandpass(sig, lo, hi):
    return lowpass(highpass(sig, lo), hi)


def gain(sig, g):
    return array("f", (v * g for v in sig))


def mix(*parts, length=None):
    """parts: (signal, start_sec, gain)."""
    total = length or max(n(st) + len(s) for s, st, _ in parts)
    out = array("f", [0.0]) * total
    for s, st, g in parts:
        o = n(st)
        for i, v in enumerate(s):
            if 0 <= o + i < total:
                out[o + i] += v * g
    return out


def pluck(freq, dur, bright=0.5, seed=3):
    """Karplus-Strong string: a soft woody twang."""
    rng = random.Random(seed)
    period = max(2, int(SR / freq))
    buf = [rng.uniform(-1, 1) for _ in range(period)]
    out = array("f", [0.0]) * n(dur)
    for i in range(len(out)):
        j = i % period
        v = buf[j]
        nxt = buf[(j + 1) % period]
        buf[j] = (v * (1 - bright) + nxt * bright) * 0.996
        out[i] = v
    return out


def bell(freq, dur, partials=((1, 1.0, 1.0), (2.76, 0.45, 0.6), (5.4, 0.25, 0.35), (8.93, 0.12, 0.2)), tau=0.6):
    """Glassy chime from inharmonic partials, each with its own decay."""
    out = array("f", [0.0]) * n(dur)
    for mult, amp, tmul in partials:
        f = freq * mult
        for i in range(len(out)):
            t = i / SR
            out[i] += amp * math.sin(2 * math.pi * f * t) * math.exp(-t / (tau * tmul))
    return out


def reverb(sig, wet=0.18, room=0.35):
    """A small warm room: four damped feedback combs."""
    tail = n(room * 2.5)
    dry = array("f", sig) + array("f", [0.0]) * tail
    out = array("f", dry)
    for dms, fb in ((29.7, 0.72), (37.1, 0.68), (41.1, 0.65), (43.7, 0.62)):
        d = n(dms / 1000 * (0.6 + room))
        buf = array("f", [0.0]) * len(dry)
        lp = 0.0
        for i in range(len(dry)):
            prev = buf[i - d] if i >= d else 0.0
            lp = lp * 0.4 + prev * 0.6
            buf[i] = dry[i] + lp * fb
            out[i] += buf[i] * wet / 4
    return out


def finish(sig, rms_db=-18.0, peak=0.9, fade_in=0.002, fade_out=0.01):
    """Remove DC, match loudness (RMS of the loud part), soft-limit peaks, fade the ends so nothing ever clicks."""
    if not sig:
        return sig
    mean = sum(sig) / len(sig)
    sig = array("f", (v - mean for v in sig))
    while len(sig) > SR // 50 and abs(sig[-1]) < 1e-4:   # trim silent tail
        sig.pop()
    m = max(abs(v) for v in sig) or 1.0
    loud = [v for v in sig if abs(v) > m * 0.05] or list(sig)   # loudness of the part you actually hear
    rms = math.sqrt(sum(v * v for v in loud) / len(loud)) or 1.0
    g = 10 ** (rms_db / 20) / rms
    sig = array("f", (peak * math.tanh(v * g / peak) for v in sig))   # gentle limiter instead of hard clipping
    fi, fo = n(fade_in), n(fade_out)
    for i in range(min(fi, len(sig))):
        sig[i] *= i / fi
    for i in range(min(fo, len(sig))):
        sig[-1 - i] *= i / fo
    return sig


LEVEL = {"ui": -24.0, "amb": -27.0}   # everything else: -18 dB RMS, so all effects feel equally loud


def write(name, sig, what, peak=None):
    OUT.mkdir(parents=True, exist_ok=True)
    sig = finish(lowpass(sig, 9000), LEVEL.get(name.split("_")[0], -18.0))
    with wave.open(str(OUT / f"{name}.wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, v)) * 32767)) for v in sig))
    DOC.append((name, len(sig) / SR, what))


def glide(f0, f1, dur, curve=1.0):
    return lambda t: f0 * (f1 / f0) ** min(1.0, (t / dur) ** curve)


# ---------------------------------------------------------------- creatures: one "hello" per kind

def creature_sounds():
    # leafy plant / sprout: a rustle of leaves under a two-note "bip-bup"
    rustle = env(bandpass(noise(0.22, 11), 1800, 6000), 0.01, 0.05, 0.3, 0.12)
    tune = mix((env(osc("tri", note("E5"), 0.09), 0.004, 0.03, 0.5, 0.04), 0.0, 1),
               (env(osc("tri", note("A5"), 0.14), 0.004, 0.04, 0.5, 0.08), 0.08, 1))
    write("creature_plant", reverb(mix((rustle, 0, 0.35), (tune, 0.02, 0.7))), "plant creatures (Moss Sprout, Sunflower): leaf rustle + bright bip-bup")
    # mushroom: a soft "pomf" with a tiny pop on top
    thump = expdecay(osc("sine", glide(220, 90, 0.12), 0.25), 0.07)
    pop = env(osc("tri", glide(1200, 1700, 0.03), 0.05), 0.001, 0.02, 0.2, 0.02)
    write("creature_mushroom", reverb(mix((thump, 0, 1), (pop, 0.01, 0.25), (env(lowpass(noise(0.1, 4), 1200), 0.002, 0.03, 0.1, 0.05), 0, 0.3))),
          "mushrooms (Ink Cap, Fly Agaric, Lion's Mane): soft pomf + little pop")
    # coral and anemone: underwater bubbles rising
    bub = []
    for k, (f, st) in enumerate(((520, 0.0), (680, 0.07), (900, 0.13))):
        b = expdecay(osc("sine", glide(f * 0.7, f * 1.6, 0.06, 0.6), 0.12), 0.04)
        bub.append((b, st, 0.8 - k * 0.15))
    write("creature_coral", reverb(mix(*bub), 0.25), "corals and anemones: three rising underwater bubbles")
    # vine / ivy: a playful plucked tendril
    soft = lambda sig: env(lowpass(sig, 3800), 0.004, 0.05, 0.8, 0.2)   # round off the pick so it is a twang, not a scratch
    write("creature_vine", reverb(mix((soft(expdecay(pluck(note("G4"), 0.5, 0.45), 0.18)), 0, 1),
                                      (soft(expdecay(pluck(note("D5"), 0.4, 0.5, 7), 0.12)), 0.09, 0.7))), "vines (Morning Glory, Ivy): two plucked tendril twangs")
    # cactus: prickly ticks then a perky square blip
    ticks = mix(*[(env(highpass(noise(0.012, 20 + i), 4000), 0.0005, 0.004, 0.2, 0.006), i * 0.028, 0.6) for i in range(4)])
    blip = env(osc("square", glide(note("C6"), note("E6"), 0.05), 0.12, duty=0.25), 0.002, 0.03, 0.4, 0.06)
    write("creature_cactus", mix((ticks, 0, 1), (lowpass(blip, 5000), 0.1, 0.5)), "cacti (Barrel, Prickly Pear): prickly ticks + perky blip")
    # slime: a wet squish, low and gloopy
    sq = lowpass(osc("saw", glide(180, 70, 0.25), 0.3), glide(2200, 300, 0.25))
    wet = env(bandpass(noise(0.25, 31), 300, 1600), 0.01, 0.08, 0.3, 0.12)
    write("creature_slime", reverb(mix((env(sq, 0.01, 0.1, 0.4, 0.15), 0, 0.9), (wet, 0, 0.5))), "slimes (Bog, Cave): wet gloopy squish")
    # jelly: a floaty wobble that rises
    jw = env(osc("sine", glide(note("C5"), note("G5"), 0.45), 0.5, vib=0.06, vib_rate=7), 0.04, 0.1, 0.6, 0.25)
    write("creature_jelly", reverb(mix((jw, 0, 0.9), (env(osc("sine", note("C6"), 0.5, vib=0.04, vib_rate=7.5), 0.08, 0.1, 0.3, 0.25), 0.05, 0.3)), 0.3, 0.5),
          "jellies (Moon, Lion's Mane): floaty rising wobble")
    # crystal: a glassy chime
    write("creature_crystal", reverb(mix((bell(note("E6"), 0.9), 0, 0.8), (bell(note("B6"), 0.7, tau=0.4), 0.06, 0.4)), 0.25, 0.5),
          "crystals (Quartz, Amethyst): two glassy chimes")
    # fern: a breathy little flute
    flute = lambda f, d: env(mix((osc("sine", f, d, vib=0.01, vib_rate=5), 0, 1), (lowpass(noise(d, 9), 2500), 0, 0.12)), 0.03, 0.05, 0.7, 0.07)
    write("creature_fern", reverb(mix((flute(note("A5"), 0.16), 0, 0.8), (flute(note("D6"), 0.2), 0.12, 0.8))), "ferns (Hart's Tongue, Tree Fern): breathy two-note flute")
    # moss: a muffled cushiony boop
    write("creature_moss", reverb(env(lowpass(osc("tri", glide(note("G4"), note("C5"), 0.08), 0.22), 900), 0.01, 0.06, 0.5, 0.12)),
          "mosses (Cushion, Slow): muffled cushiony boop")
    # tree: a warm woody knock with a little hum
    knock = mix((expdecay(osc("sine", 210, 0.3), 0.06), 0, 1), (expdecay(osc("sine", 470, 0.3), 0.035), 0, 0.6),
                (env(lowpass(noise(0.03, 5), 3000), 0.0005, 0.01, 0.2, 0.02), 0, 0.4))
    hum = env(osc("tri", note("G3"), 0.35, vib=0.01), 0.05, 0.1, 0.4, 0.2)
    write("creature_tree", reverb(mix((knock, 0, 1), (knock, 0.11, 0.7), (lowpass(hum, 800), 0.05, 0.35))), "trees (Bonsai, Willow): two woody knocks + warm hum")


# ---------------------------------------------------------------- snoops: each one sounds like what it is

def snoop_sounds():
    # mite: greedy chittering
    ch = osc("square", lambda t: 1500 + 300 * math.sin(2 * math.pi * 9 * t), 0.4, duty=0.3)
    am = array("f", (v * (0.5 + 0.5 * math.sin(2 * math.pi * 32 * i / SR)) ** 2 for i, v in enumerate(ch)))
    write("snoop_mite", env(lowpass(lowpass(am, 3000), 3000), 0.01, 0.05, 0.7, 0.12), "tick-mite Snoop: greedy chittering")
    # moth: dusty wing flutter
    fl = bandpass(noise(0.45, 41), 400, 2400)
    fl = array("f", (v * (0.5 + 0.5 * math.sin(2 * math.pi * 19 * i / SR)) ** 3 for i, v in enumerate(fl)))
    write("snoop_moth", env(fl, 0.02, 0.05, 0.8, 0.15), "moth Snoop: dusty wing flutter")
    # leech: a slurp
    sl = lowpass(noise(0.4, 51), glide(300, 2400, 0.35))
    tone = env(osc("sine", glide(140, 360, 0.35), 0.4), 0.02, 0.1, 0.6, 0.12)
    write("snoop_leech", env(mix((sl, 0, 0.7), (tone, 0, 0.5)), 0.02, 0.05, 0.8, 0.12), "leech Snoop: a slurp")
    # a Snoop arrives: an ominous little buzz sliding in
    bz = lowpass(osc("saw", glide(110, 160, 0.5), 0.6, vib=0.03, vib_rate=11), 1400)
    write("snoop_arrive", reverb(env(bz, 0.12, 0.1, 0.6, 0.2)), "a new Snoop shows up: ominous buzz sliding in")


# ---------------------------------------------------------------- actions

def action_sounds():
    whoosh = env(lowpass(highpass(noise(0.4, 61), glide(300, 2500, 0.35)), 5000), 0.05, 0.1, 0.5, 0.2)
    write("shoo", whoosh, "Shoo away: a quick whoosh")
    puff = env(lowpass(noise(0.3, 71, "pink"), glide(3000, 400, 0.25)), 0.002, 0.05, 0.35, 0.18)
    write("poof", reverb(mix((puff, 0, 1), (expdecay(osc("sine", glide(300, 120, 0.1), 0.2), 0.06), 0, 0.5))), "poof cloud where a Snoop vanished")
    arp = mix(*[(bell(note(x), 0.8, tau=0.35), i * 0.06, 0.6) for i, x in enumerate(("C6", "E6", "G6", "C7"))])
    write("heal", reverb(arp, 0.25, 0.5), "healing sparkle after a shoo: rising bell arpeggio")
    write("heart", reverb(mix((env(osc("tri", note("G5"), 0.12, vib=0.01), 0.01, 0.04, 0.6, 0.06), 0, 0.7),
                              (env(osc("tri", note("C6"), 0.22, vib=0.015), 0.01, 0.05, 0.6, 0.14), 0.1, 0.7))), "floating heart: a soft 'aww'")
    write("hop", env(osc("sine", lambda t: 380 * (1 + 1.2 * math.sin(math.pi * min(1, t / 0.16))), 0.18), 0.003, 0.05, 0.6, 0.08), "a happy hop: little boing")
    up = env(osc("tri", glide(note("C5"), note("C6"), 0.1), 0.13), 0.003, 0.04, 0.5, 0.06)
    write("pickup", reverb(up), "picking a creature up: rising hup")
    land = mix((expdecay(osc("sine", glide(160, 70, 0.08), 0.18), 0.05), 0, 1), (env(lowpass(noise(0.12, 81, "pink"), 900), 0.002, 0.03, 0.2, 0.08), 0, 0.6))
    write("drop", land, "setting a creature down on moss: soft landing")


# ---------------------------------------------------------------- moods

def mood_sounds():
    gig = mix(*[(env(osc("tri", f, 0.07, vib=0.03, vib_rate=14), 0.003, 0.03, 0.5, 0.03), i * 0.065, 0.7)
                for i, f in enumerate((note("E6"), note("G6"), note("E6"), note("A6")))])
    write("mood_happy", reverb(gig), "happy bubble: a tiny giggle")
    gr = osc("square", glide(160, 105, 0.35), 0.4, duty=0.4)
    gr = array("f", (v * (0.6 + 0.4 * math.sin(2 * math.pi * 13 * i / SR)) for i, v in enumerate(gr)))
    write("mood_grumpy", env(lowpass(gr, 900), 0.02, 0.08, 0.6, 0.15), "grumpy bubble: a low 'hmph' grumble")
    qs = mix((env(osc("sine", glide(note("A4"), note("D4"), 0.6), 0.65, vib=0.05, vib_rate=4), 0.03, 0.1, 0.6, 0.2), 0, 0.7),
             (env(osc("sine", glide(note("A4") * 1.02, note("D4") * 1.03, 0.6), 0.65, vib=0.05, vib_rate=4.3), 0.03, 0.1, 0.6, 0.2), 0, 0.5))
    write("mood_sick", reverb(qs), "sick bubble: a queasy wobbling slide down")
    breath = lowpass(noise(1.3, 91, "pink"), 700)
    breath = array("f", (v * math.sin(math.pi * i / len(breath)) ** 2 for i, v in enumerate(breath)))
    write("mood_sleepy", mix((breath, 0, 1), (env(osc("tri", note("A2"), 1.2, vib=0.02), 0.3, 0.2, 0.5, 0.4), 0.05, 0.25)), "napping: one soft snore")


# ---------------------------------------------------------------- evolution

def evolution_sounds():
    write("evo_tick", env(lowpass(osc("square", note("A5"), 0.05, duty=0.25), 3500), 0.003, 0.015, 0.4, 0.02), "evolution flicker tick (pitch rises as it speeds up)")
    rise = lowpass(highpass(noise(0.7, 101), glide(200, 4000, 0.6)), 7000)
    boom = expdecay(osc("sine", glide(120, 45, 0.4), 0.8), 0.3)
    write("evo_burst", reverb(mix((env(rise, 0.3, 0.1, 0.8, 0.2), 0, 0.6), (boom, 0.55, 1.0), (bell(note("C7"), 0.6, tau=0.3), 0.6, 0.3)), 0.25, 0.6),
          "evolution burst: rising rush into a soft boom")
    lead = [("C5", 0.0, 0.12), ("E5", 0.12, 0.12), ("G5", 0.24, 0.12), ("C6", 0.36, 0.36), ("B5", 0.74, 0.12), ("C6", 0.86, 0.6)]
    parts = [(env(lowpass(osc("square", note(x), d + 0.05, duty=0.25, vib=0.006), 6000), 0.005, 0.04, 0.6, 0.05), st, 0.45) for x, st, d in lead]
    parts += [(env(osc("tri", note(x), d), 0.005, 0.05, 0.7, 0.08), st, 0.5) for x, st, d in (("C3", 0.0, 0.36), ("G3", 0.36, 0.38), ("C3", 0.74, 0.7))]
    parts += [(bell(note(x), 1.2, tau=0.5), 0.86 + i * 0.04, 0.18) for i, x in enumerate(("E6", "G6", "C7"))]
    write("evo_fanfare", reverb(mix(*parts), 0.2, 0.5), "evolution reveal: a short chiptune fanfare")


# ---------------------------------------------------------------- interface

def ui_sounds():
    tock = mix((expdecay(osc("sine", 820, 0.08), 0.018), 0, 1), (expdecay(osc("sine", 1650, 0.08), 0.01), 0, 0.4),
               (env(highpass(noise(0.01, 111), 2000), 0.0005, 0.004, 0.2, 0.005), 0, 0.3))
    write("ui_tap", tock, "buttons: a small wooden tock")
    write("ui_toggle_on", mix((env(osc("tri", note("E6"), 0.04), 0.001, 0.015, 0.4, 0.02), 0, 0.7), (env(osc("tri", note("A6"), 0.05), 0.001, 0.02, 0.4, 0.03), 0.035, 0.7)),
          "switch on: two clicks up")
    write("ui_toggle_off", mix((env(osc("tri", note("A5"), 0.04), 0.001, 0.015, 0.4, 0.02), 0, 0.7), (env(osc("tri", note("E5"), 0.05), 0.001, 0.02, 0.4, 0.03), 0.035, 0.7)),
          "switch off: two clicks down")
    paper = lambda f0, f1: env(bandpass(noise(0.22, 121), 800, 5000) if False else lowpass(highpass(noise(0.22, 121), glide(f0, f1, 0.2)), 6000), 0.03, 0.06, 0.5, 0.1)
    write("ui_sheet_open", paper(500, 2500), "pulling the parchment sheet up: paper slide")
    write("ui_sheet_close", paper(2500, 500), "pushing the sheet down: paper slide back")
    flip = highpass(noise(0.14, 131), 1500)
    flip = array("f", (v * (0.5 + 0.5 * math.sin(2 * math.pi * 40 * i / SR)) for i, v in enumerate(flip)))
    write("ui_info", env(flip, 0.005, 0.04, 0.4, 0.07), "opening an i sheet: page flip")


# ---------------------------------------------------------------- ambience (seamless loops)

def loop_seamless(sig, xfade):
    """Crossfade the end into the start so the loop has no seam."""
    x = n(xfade)
    body = array("f", sig[:len(sig) - x])
    for i in range(x):
        g = i / x
        body[i] = body[i] * g + sig[len(sig) - x + i] * (1 - g)
    return body


def ambience():
    dur = 12.0
    wind = lowpass(noise(dur + 1, 141, "pink"), lambda t: 500 + 300 * math.sin(2 * math.pi * t / 6.5))
    wind = array("f", (v * (0.55 + 0.45 * math.sin(2 * math.pi * i / SR / 6.5) ** 2) for i, v in enumerate(wind)))
    rng = random.Random(7)
    birds = []
    for k in range(6):   # little chirps, far away
        st = rng.uniform(0.5, dur - 1)
        f = rng.choice((2600, 3100, 3500))
        for j in range(rng.randint(2, 4)):
            c = env(osc("sine", glide(f, f * 1.35, 0.06, 0.7), 0.07), 0.005, 0.02, 0.5, 0.03)
            birds.append((c, st + j * 0.09, 0.12))
    day = mix((wind, 0, 0.5), *birds, length=n(dur + 1))
    write("amb_day", loop_seamless(reverb(day, 0.3, 0.6)[:n(dur + 1)], 1.0), "day ambience inside the jar: soft breeze, far birds (loop)")
    crickets = []
    for k in range(int(dur / 0.9)):   # cricket trills in a relaxed rhythm
        st = k * 0.9 + rng.uniform(0, 0.15)
        tr = osc("sine", 4300 + rng.uniform(-150, 150), 0.18)
        tr = array("f", (v * (0.5 + 0.5 * math.sin(2 * math.pi * 55 * i / SR)) ** 2 for i, v in enumerate(tr)))
        crickets.append((env(tr, 0.01, 0.04, 0.6, 0.06), st, 0.1))
    night = mix((lowpass(wind, 400), 0, 0.35), *crickets, length=n(dur + 1))
    write("amb_night", loop_seamless(reverb(night, 0.25, 0.6)[:n(dur + 1)], 1.0), "night ambience inside the jar: crickets, faint breeze (loop)")


def main():
    for old in OUT.glob("*.wav"):
        old.unlink()
    creature_sounds()
    snoop_sounds()
    action_sounds()
    mood_sounds()
    evolution_sounds()
    ui_sounds()
    ambience()
    rows = ["# Sounds", "", "Synthesized from scratch by `python3 scripts/gen_sounds.py` (no samples, no downloads) into",
            "`app/src/main/assets/sounds/`. Replace any WAV with your own (same name) to change it.", "",
            "| Sound | Length | Plays when |", "|---|---|---|"]
    rows += [f"| {nm} | {d:.2f} s | {w} |" for nm, d, w in DOC]
    (ROOT / "docs/SOUNDS.md").write_text("\n".join(rows) + "\n")
    print(f"{len(DOC)} sounds")


if __name__ == "__main__":
    main()

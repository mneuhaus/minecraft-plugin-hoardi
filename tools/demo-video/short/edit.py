"""Cuts the vertical YouTube short: the short takes laid onto the voice-over, word-by-word captions,
hook title, end card, sound effects and music.

    uv run --with pillow --with numpy short/edit.py en|de [hall-take] [setup-take]

Needs short/vo.py output (vo-<lang>.mp3/.json) plus music.mp3, sfx-*.mp3 and mc-*.ogg in
~/.cache/hoardi-demo/short, and the takes recorded with take.sh (hall: vdump vshelves vfind vgrow
vend, setup room: vsetup vnet). Writes out/hoardi-short-<lang>.mp4 (1080x1920, 60 fps).

Picture follows the narration: each beat of short/script.json is one segment. Events the narration
names are anchored to the spoken word (lid closes on "close", first shelf on "Sneak"); static
stretches (an open chest GUI) absorb the length differences between English and German; pure
camera moves are retimed to fit their beat. Chest GUIs are blown up 2x with nearest-neighbour so
the pixel art stays crisp.
"""
import json
import subprocess
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

BASE = Path.home() / ".cache/hoardi-demo"
TAKES = BASE / "takes"
SHORT = BASE / "short"
HERE = Path(__file__).parent
ROOT = HERE.parent.parent.parent
FONT = BASE / "fonts/Inter.ttf"
W, H, FPS = 1080, 1920, 60

# where the chest GUI sits in a 1080x1920 frame (GUI scale 3) and how it is blown up
GUI_ZOOM, GUI_CROP = 2, (270, 572)
CAPTION_Y = 1330
FIRST_SHELF_DELAY = 1.9 + 0.08  # vsetup: first shelf placed at 1.9 s, client shows it ~2 frames later
FLIP_AFTER_CLOSE = 0.6           # Hoardi re-sorts and the shelves flip ~0.6 s after the lid closes


def font(size, weight="Black"):
    f = ImageFont.truetype(str(FONT), size)
    f.set_variation_by_name(weight)
    return f


# ------------------------------------------------------------------ takes

class Take:
    """A recording plus the director's shot marks, all in seconds of the movie."""

    def __init__(self, name):
        self.mov = TAKES / f"{name}.mov"
        start = int((TAKES / f"{name}.mov.start").read_text().strip())
        self.shots = {}
        for line in (TAKES / f"{name}.marks").read_text().splitlines():
            ms, what = line.split(" ", 1)
            kind, _, shot = what.partition(" ")
            if kind in ("start", "end"):
                self.shots.setdefault(shot, {})[kind] = (int(ms) - start) / 1000
        self._luma = None

    def start(self, shot):
        return self.shots[shot]["start"]

    def end(self, shot):
        return self.shots[shot]["end"]

    def luma(self):
        """Mean brightness of the top band per 1/60 s: drops sharply while a chest GUI is open."""
        if self._luma is None:
            cache = self.mov.with_suffix(".luma.npy")
            if cache.exists() and cache.stat().st_mtime > self.mov.stat().st_mtime:
                self._luma = np.load(cache)
            else:
                raw = subprocess.run(["ffmpeg", "-v", "error", "-i", str(self.mov), "-vf",
                                      f"fps={FPS},scale=36:64,format=gray", "-f", "rawvideo", "-"],
                                     capture_output=True, check=True).stdout
                frames = np.frombuffer(raw, np.uint8).reshape(-1, 64, 36)
                self._luma = frames[:, :8, :].mean(axis=(1, 2))
                np.save(cache, self._luma)
        return self._luma

    def gui_span(self, shot, within):
        """(open, close) movie seconds of the chest GUI in a shot. `within` is the window (seconds into
        the shot) where the director keeps the camera still, so only the GUI changes the brightness."""
        lum = self.luma()
        a = int((self.start(shot) + within[0]) * FPS)
        b = int((self.start(shot) + within[1]) * FPS)
        seg = lum[a:b]
        dark = np.flatnonzero(seg < (seg.min() + seg.max()) / 2)
        if len(dark) < 12:
            sys.exit(f"no chest GUI found in {shot} {within}")
        return (a + dark[0]) / FPS, (a + dark[-1] + 1) / FPS


class Segment:
    """Timeline [t0, t1) shows movie [f0, f1) of a take, linearly retimed."""

    def __init__(self, take, f0, f1, t0, t1, gui=()):
        self.take, self.f0, self.f1, self.t0, self.t1 = take, f0, f1, t0, t1
        self.gui = list(gui)

    def to_timeline(self, f):
        return self.t0 + (f - self.f0) * (self.t1 - self.t0) / (self.f1 - self.f0)

    def frames(self):
        """Yields (timeline time, RGB frame) at FPS."""
        n_out = round((self.t1 - self.t0) * FPS)
        n_in = max(1, round((self.f1 - self.f0) * FPS))
        proc = subprocess.Popen(["ffmpeg", "-v", "error", "-ss", f"{self.f0:.3f}", "-i", str(self.take.mov),
                                 "-t", f"{self.f1 - self.f0 + 0.2:.3f}", "-vf", f"fps={FPS},scale={W}:{H}",
                                 "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], stdout=subprocess.PIPE)
        size, have, frame = W * H * 3, -1, None
        for i in range(n_out):
            want = min(int(i * n_in / n_out), n_in - 1)
            while have < want:
                buf = proc.stdout.read(size)
                if len(buf) < size:
                    break
                frame, have = np.frombuffer(buf, np.uint8).reshape(H, W, 3), have + 1
            f = self.f0 + want / FPS
            img = frame
            if any(a <= f < b for a, b in self.gui):
                x, y = GUI_CROP
                crop = img[y:y + H // GUI_ZOOM, x:x + W // GUI_ZOOM]
                img = crop.repeat(GUI_ZOOM, axis=0).repeat(GUI_ZOOM, axis=1)
            yield self.t0 + i / FPS, img
        proc.stdout.close()
        proc.wait()


def word_time(vo, word, offset):
    for w in vo["words"]:
        if w["text"] == word:
            return w["start"] + offset
    sys.exit(f"anchor word {word!r} not in the voice-over")


def plan(lang, hall, setup, vo, script):
    """Segments and sound effects (file, timeline seconds, gain dB) for one language."""
    beats = vo["beats"]
    starts = [b["start"] for b in beats]
    end_total = vo["words"][-1]["end"] + 1.9
    segs, sfx = [], []

    # vdump, part 1: GUI opens, loot pours in (first 1.25 s of the open GUI)
    g_open, g_close = hall.gui_span("vdump", (0, 7.4))  # GUI open 0 s, closes 6.0 s
    pour = 1.25
    segs.append(Segment(hall, g_open, g_open + pour, 0, pour, [(g_open, g_close)]))
    # vdump, part 2: jump inside the static GUI so the lid closes on the anchor word, then the
    # shelves flip and the camera pulls back until the next beat
    t_close = word_time(vo, *script["anchors"]["close"][lang])
    f0 = g_close - (t_close - pour)
    segs.append(Segment(hall, f0, f0 + starts[3] - pour, pour, starts[3], [(g_open, g_close)]))
    sfx += [("mc-block-chest-open.ogg", 0.0, -6)]
    sfx += [(f"mc-block-shelf-place_item{1 + k % 4}.ogg", 0.12 + k * 0.09, -12) for k in range(8)]
    sfx += [("mc-block-chest-close1.ogg", t_close, -3),
            ("mc-block-shelf-multi_swap1.ogg", t_close + FLIP_AFTER_CLOSE, 0),
            ("sfx-pop.mp3", t_close + FLIP_AFTER_CLOSE, -10),
            ("sfx-ding.mp3", t_close + FLIP_AFTER_CLOSE + 0.05, -12)]

    # vshelves: the slide, retimed to the beat
    s = Segment(hall, hall.start("vshelves") + 0.05, hall.end("vshelves") - 0.05, starts[3], starts[4])
    segs.append(s)

    # vfind: the chest opens 0.65 s before the beat ends
    o, c = hall.gui_span("vfind", (2.6, 5.6))  # GUI open 3.2 s, closes 5.4 s
    t_open = starts[5] - 0.65
    f0 = max(hall.start("vfind") + 0.05, o - (t_open - starts[4]))
    segs.append(Segment(hall, f0, f0 + starts[5] - starts[4], starts[4], starts[5], [(o, c)]))
    sfx += [("mc-block-chest-open.ogg", t_open, -6)]

    # vsetup: first shelf on "Sneak"
    t_shelf = word_time(vo, *script["anchors"]["first_shelf"][lang])
    f_shelf = setup.start("vsetup") + FIRST_SHELF_DELAY
    f0 = f_shelf - (t_shelf - starts[5])
    segs.append(Segment(setup, f0, f0 + starts[6] - starts[5], starts[5], starts[6]))
    sfx += [(f"mc-dig-wood{1 + k % 4}.ogg", t_shelf + k * 0.12, -9) for k in range(15)]
    sfx += [("sfx-pop.mp3", t_shelf + 14 * 0.12 + 0.1, -10)]

    # vnet and vgrow: camera moves retimed to their beats
    s = Segment(setup, setup.start("vnet") + 0.05, setup.end("vnet") - 0.05, starts[6], starts[7])
    segs.append(s)
    sfx += [("sfx-ding.mp3", starts[6] + 0.08, -12)]
    s = Segment(hall, hall.start("vgrow") + 0.05, hall.end("vgrow") - 0.05, starts[7], starts[8])
    segs.append(s)
    g = hall.start("vgrow")
    sfx += [(f"mc-dig-wood{k + 1}.ogg", s.to_timeline(g + t), -6) for k, t in enumerate((0.3, 0.55, 0.8))]
    sfx += [("mc-block-shelf-multi_swap2.ogg", s.to_timeline(g + 1.35), 0)]

    # vend: the aisle under the end card
    f0 = hall.start("vend") + 0.05
    segs.append(Segment(hall, f0, f0 + end_total - starts[8], starts[8], end_total))
    sfx += [("sfx-ding.mp3", starts[8] + 0.05, -6)]

    # a soft whoosh into every cut after the hook
    sfx += [("sfx-whoosh.mp3", t - 0.18, -16) for t in starts[3:]]
    return segs, sfx, end_total


# ------------------------------------------------------------------ overlays

def text_image(lines, size, colors, stroke=10, weight="Black", spacing=12):
    """RGBA image of centred lines; colors per line (or per word list for captions)."""
    f = font(size, weight)
    probe = ImageDraw.Draw(Image.new("RGBA", (1, 1)))
    boxes = [probe.textbbox((0, 0), line, font=f, stroke_width=stroke) for line in lines]
    width = max(b[2] - b[0] for b in boxes)
    height = sum(b[3] - b[1] for b in boxes) + spacing * (len(lines) - 1)
    img = Image.new("RGBA", (width + 8, height + 8), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    y = 4
    for line, box, color in zip(lines, boxes, colors):
        x = 4 + (width - (box[2] - box[0])) // 2 - box[0]
        d.text((x, y - box[1]), line, font=f, fill=color, stroke_width=stroke, stroke_fill=(0, 0, 0, 255))
        y += box[3] - box[1] + spacing
    return img


class Captions:
    """Groups of up to three words; the word being spoken is yellow, a new group pops in."""

    WHITE, YELLOW = (255, 255, 255, 255), (255, 216, 74, 255)

    def __init__(self, vo, until):
        words = vo["words"]
        self.groups = []
        cur = []
        for k, w in enumerate(words):
            cur.append(k)
            last_of_beat = any(k == b["words"][-1] for b in vo["beats"])
            if len(cur) == 3 or w["text"][-1] in ".,?!" or last_of_beat:
                self.groups.append(cur)
                cur = []
        if cur:
            self.groups.append(cur)
        self.words, self.until, self.cache = words, until, {}

    def at(self, t):
        if t >= self.until:
            return None
        for gi, g in enumerate(self.groups):
            g_start = self.words[g[0]]["start"]
            g_end = self.words[g[-1]]["end"] + 0.25
            nxt = self.groups[gi + 1] if gi + 1 < len(self.groups) else None
            if nxt:
                g_end = min(g_end, self.words[nxt[0]]["start"])
            if g_start <= t < g_end:
                active = max([k for k in g if self.words[k]["start"] <= t], default=g[0])
                img = self.render(gi, active)
                age = t - g_start
                if age < 0.12:  # pop in
                    s = 0.82 + 0.18 * (age / 0.12) ** 0.5
                    img = img.resize((max(1, int(img.width * s)), max(1, int(img.height * s))), Image.LANCZOS)
                return img
        return None

    def render(self, gi, active):
        key = (gi, active)
        if key not in self.cache:
            g = self.groups[gi]
            f = font(92)
            stroke = 11
            probe = ImageDraw.Draw(Image.new("RGBA", (1, 1)))
            texts = [self.words[k]["text"] for k in g]
            widths = [probe.textbbox((0, 0), t, font=f, stroke_width=stroke)[2] for t in texts]
            space = probe.textlength(" ", font=f)
            total = sum(widths) + space * (len(texts) - 1)
            size = 92 if total <= 900 else int(92 * 900 / total)
            f = font(size)
            widths = [probe.textbbox((0, 0), t, font=f, stroke_width=stroke)[2] for t in texts]
            space = probe.textlength(" ", font=f)
            total = int(sum(widths) + space * (len(texts) - 1))
            asc, desc = f.getmetrics()
            img = Image.new("RGBA", (total + 2 * stroke + 8, asc + desc + 2 * stroke + 8), (0, 0, 0, 0))
            d = ImageDraw.Draw(img)
            x = stroke + 4
            for k, t, wdt in zip(g, texts, widths):
                color = self.YELLOW if k == active else self.WHITE
                d.text((x, stroke + 4), t, font=f, fill=color, stroke_width=stroke, stroke_fill=(0, 0, 0, 255))
                x += wdt + space
            self.cache[key] = img
        return self.cache[key]


def paste_center(frame, img, cx, cy, alpha=1.0):
    if alpha < 1.0:
        img = img.copy()
        img.putalpha(img.getchannel("A").point(lambda a: int(a * alpha)))
    frame.alpha_composite(img, (int(cx - img.width / 2), int(cy - img.height / 2)))


def ease_out_back(u):
    c1, c3 = 1.70158, 2.70158
    return 1 + c3 * (u - 1) ** 3 + c1 * (u - 1) ** 2


# ------------------------------------------------------------------ audio

def mix_audio(lang, sfx, duration, out_wav):
    inputs = ["-i", str(SHORT / f"vo-{lang}.mp3"), "-i", str(SHORT / "music.mp3")]
    chains = [
        "[0:a]aresample=48000,volume=1.0[vo]",
        f"[1:a]aresample=48000,volume=-17dB,afade=t=out:st={duration - 1.6:.2f}:d=1.6[mus]",
    ]
    labels = ["[vo]", "[mus]"]
    for i, (name, t, gain) in enumerate(sfx):
        inputs += ["-i", str(SHORT / name)]
        ms = max(0, int(t * 1000))
        chains.append(f"[{i + 2}:a]aresample=48000,aformat=channel_layouts=stereo,volume={gain}dB,"
                      f"adelay={ms}|{ms}[s{i}]")
        labels.append(f"[s{i}]")
    chains.append(f"{''.join(labels)}amix=inputs={len(labels)}:normalize=0:duration=longest,"
                  f"apad,atrim=0:{duration:.3f},loudnorm=I=-14:TP=-1.5:LRA=11[out]")
    subprocess.run(["ffmpeg", "-v", "error", "-y", *inputs, "-filter_complex", ";".join(chains),
                    "-map", "[out]", "-ar", "48000", str(out_wav)], check=True)


# ------------------------------------------------------------------ main

def main():
    lang = sys.argv[1]
    hall = Take(sys.argv[2] if len(sys.argv) > 2 else "v3")
    setup = Take(sys.argv[3] if len(sys.argv) > 3 else "vs2")
    script = json.loads((HERE / "script.json").read_text())
    vo = json.loads((SHORT / f"vo-{lang}.json").read_text())
    segs, sfx, duration = plan(lang, hall, setup, vo, script)
    for s in segs:
        print(f"  {s.t0:6.2f}-{s.t1:6.2f}  {s.take.mov.stem}  {s.f0:7.2f}-{s.f1:7.2f}  x{(s.f1 - s.f0) / (s.t1 - s.t0):.2f}")

    end_start = vo["beats"][8]["start"]
    captions = Captions(vo, until=end_start)
    hook = text_image(script["hook"][lang], 70, [(255, 255, 255, 255), (255, 216, 74, 255)])
    icon = Image.open(ROOT / "assets/icon-modrinth.png").convert("RGBA").resize((320, 320), Image.LANCZOS)
    title = text_image(["Hoardi"], 170, [(255, 216, 74, 255)], stroke=12)
    sub = text_image([script["endcard"][lang][0]], 70, [(255, 255, 255, 255)], stroke=9)
    sub2 = text_image([script["endcard"][lang][1]], 46, [(230, 230, 230, 255)], stroke=7, weight="Bold")
    hook_until = max(2.9, vo["beats"][1]["start"] + 1.0)

    out = ROOT / f"tools/demo-video/out/hoardi-short-{lang}.mp4"
    work = SHORT / f"work-{lang}"
    work.mkdir(exist_ok=True)
    wav = work / "mix.wav"
    mix_audio(lang, sfx, duration, wav)

    enc = subprocess.Popen(["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24",
                            "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-", "-i", str(wav),
                            "-c:v", "libx264", "-preset", "slow", "-crf", "17", "-pix_fmt", "yuv420p",
                            "-c:a", "aac", "-b:a", "192k", "-shortest", "-movflags", "+faststart", str(out)],
                           stdin=subprocess.PIPE)
    for seg in segs:
        for t, rgb in seg.frames():
            frame = Image.fromarray(rgb).convert("RGBA")
            if t < hook_until:
                a = min(1.0, (hook_until - t) / 0.25)
                paste_center(frame, hook, W / 2, 175, a)
            cap = captions.at(t)
            if cap is not None:
                paste_center(frame, cap, W / 2, CAPTION_Y)
            if t >= end_start:
                u = min(1.0, (t - end_start) / 0.35)
                shade = Image.new("RGBA", (W, H), (10, 8, 6, int(150 * u)))
                frame.alpha_composite(shade)
                s = ease_out_back(u)
                ic = icon.resize((max(1, int(320 * s)), max(1, int(320 * s))), Image.LANCZOS)
                paste_center(frame, ic, W / 2, 610, u)
                paste_center(frame, title, W / 2, 900, u)
                v = min(1.0, max(0.0, (t - end_start - 0.9) / 0.3))
                paste_center(frame, sub, W / 2, 1040, v)
                paste_center(frame, sub2, W / 2, 1130, v)
            enc.stdin.write(frame.convert("RGB").tobytes())
    enc.stdin.close()
    enc.wait()
    print(f"{duration:.2f}s -> {out}")


if __name__ == "__main__":
    main()

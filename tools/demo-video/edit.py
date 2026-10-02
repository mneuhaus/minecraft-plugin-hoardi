"""Cuts a recorded take into the Hoardi demo video: one segment per shot, captions, crossfades.

    uv run --with pillow edit.py <take-name> [out.mp4] [main|setup]

Reads ~/.cache/hoardi-demo/takes/<take>.mov, <take>.mov.start (epoch ms of the first frame) and
<take>.marks (shot start/end marks written by the HoardiDemo director plugin).
"""
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

BASE = Path.home() / ".cache/hoardi-demo"
FONT = BASE / "fonts/Inter.ttf"
W, H = 1920, 1080
XFADE = 0.4

# shot -> (trim at start, trim at end, captions [(text, from, to)] in shot time, title card?)
MAIN = {
    "hall": (0.0, 0.1, [], ("Hoardi", "Auto-sorting chest networks for Paper servers")),
    "dump": (0.2, 0.3, [("Dump your loot into any chest", 2.4, 8.9)], None),
    "sorted": (0.0, 0.2, [("Close it, and Hoardi sorts everything into the network", 0.2, 6.6)], None),
    "find": (0.0, 0.2, [("Shelves show what's inside. Click one to open its chest.", 0.6, 7.6)], None),
    "grow": (0.6, 2.4, [("Out of room? Add chests. Hoardi re-sorts on its own.", 0.4, 8.6)], None),
    "end": (0.0, 0.0, [], ("Hoardi", "Free & open source  ·  Modrinth & Hangar  ·  Paper 26.1.2+")),
}

SETUP = {
    "setup": (0.0, 0.0, [
        ("Place your chests (double chests work too)", 0.5, 4.2),
        ("Sneak + place a shelf on each one", 4.4, 8.6),
        ("Single chests with oak shelves", 9.4, 11.1),
        ("Barrels with spruce shelves on top", 12.1, 13.7),
        ("Each wood type is its own network", 14.6, 17.4),
    ], None),
}

PLANS = {"main": MAIN, "setup": SETUP}


def font(size, weight):
    f = ImageFont.truetype(str(FONT), size)
    f.set_variation_by_name(weight)
    return f


def caption_png(text, path):
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    f = font(46, "SemiBold")
    box = ImageDraw.Draw(img).textbbox((0, 0), text, font=f)
    tw, th = box[2] - box[0], box[3] - box[1]
    pad_x, pad_y = 40, 26
    x0 = (W - tw) // 2 - pad_x
    y0 = H - 150 - th - pad_y
    plate = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(plate).rounded_rectangle(
        (x0, y0, x0 + tw + 2 * pad_x, y0 + th + 2 * pad_y), radius=22, fill=(12, 10, 8, 175))
    img = Image.alpha_composite(img, plate)
    ImageDraw.Draw(img).text((x0 + pad_x - box[0], y0 + pad_y - box[1]), text, font=f, fill=(255, 250, 240, 255))
    img.save(path)


def title_png(title, subtitle, path):
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    # soft vignette behind the text so it reads on the busy shelves
    shade = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(shade).ellipse((W * 0.18, H * 0.22, W * 0.82, H * 0.78), fill=(8, 6, 4, 170))
    img = Image.alpha_composite(img, shade.filter(ImageFilter.GaussianBlur(90)))
    d = ImageDraw.Draw(img)
    ft, fs = font(168, "ExtraBold"), font(44, "Medium")
    tb, sb = d.textbbox((0, 0), title, font=ft), d.textbbox((0, 0), subtitle, font=fs)
    ty = H // 2 - 130
    d.text(((W - (tb[2] - tb[0])) // 2 - tb[0] + 4, ty - tb[1] + 5), title, font=ft, fill=(0, 0, 0, 140))
    d.text(((W - (tb[2] - tb[0])) // 2 - tb[0], ty - tb[1]), title, font=ft, fill=(255, 214, 120, 255))
    d.text(((W - (sb[2] - sb[0])) // 2 - sb[0], ty + 200 - sb[1]), subtitle, font=fs, fill=(255, 250, 240, 255))
    img.save(path)


def main():
    take = sys.argv[1]
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else BASE / f"hoardi-demo-{take}.mp4"
    plan = PLANS[sys.argv[3] if len(sys.argv) > 3 else "main"]
    takes = BASE / "takes"
    mov = takes / f"{take}.mov"
    start_ms = int((takes / f"{take}.mov.start").read_text().strip())
    marks = {}
    for line in (takes / f"{take}.marks").read_text().splitlines():
        parts = line.split()
        if len(parts) == 3 and parts[1] in ("start", "end"):
            marks[(parts[1], parts[2])] = (int(parts[0]) - start_ms) / 1000

    work = BASE / "edit" / take
    work.mkdir(parents=True, exist_ok=True)
    inputs, filters, segments = ["-i", str(mov)], [], []
    for i, (shot, (cut_in, cut_out, captions, title)) in enumerate(plan.items()):
        t0 = marks[("start", shot)] + cut_in
        t1 = marks[("end", shot)] - cut_out
        dur = t1 - t0
        chain = f"[0:v]trim={t0:.3f}:{t1:.3f},setpts=PTS-STARTPTS,fps=60,eq=gamma=1.08:saturation=1.1[s{i}]"
        filters.append(chain)
        label = f"s{i}"
        overlays = [(text, max(a - cut_in, 0.1), b - cut_in, None) for text, a, b in captions]
        if title:
            overlays.append((title, 0.5 if shot == "hall" else 0.3, dur - (0.6 if shot == "hall" else 0), "title"))
        for j, (text, a, b, kind) in enumerate(overlays):
            png = work / f"{shot}-{j}.png"
            if kind == "title":
                title_png(*text, png)
            else:
                caption_png(text, png)
            inputs += ["-loop", "1", "-framerate", "60", "-t", f"{dur:.3f}", "-i", str(png)]
            n = inputs.count("-i") - 1
            fade_out = min(b, dur) - 0.35
            filters.append(
                f"[{n}:v]format=rgba,fade=in:st={a:.2f}:d=0.35:alpha=1,fade=out:st={fade_out:.2f}:d=0.35:alpha=1[o{i}{j}]")
            filters.append(f"[{label}][o{i}{j}]overlay=0:0:shortest=1[s{i}o{j}]")
            label = f"s{i}o{j}"
        segments.append((label, dur))

    # crossfade chain
    acc, acc_dur = segments[0]
    for k, (label, dur) in enumerate(segments[1:], start=1):
        offset = acc_dur - XFADE
        filters.append(f"[{acc}][{label}]xfade=transition=fade:duration={XFADE}:offset={offset:.3f}[x{k}]")
        acc, acc_dur = f"x{k}", acc_dur + dur - XFADE
    filters.append(f"[{acc}]fade=in:st=0:d=0.5,fade=out:st={acc_dur - 0.6:.3f}:d=0.6,format=yuv420p[v]")

    cmd = ["ffmpeg", "-loglevel", "error", "-y", *inputs, "-filter_complex", ";".join(filters),
           "-map", "[v]", "-c:v", "libx264", "-preset", "slow", "-crf", "18", "-r", "60",
           "-movflags", "+faststart", str(out)]
    subprocess.run(cmd, check=True)
    print(f"{out}  ({acc_dur:.1f}s)")


if __name__ == "__main__":
    main()

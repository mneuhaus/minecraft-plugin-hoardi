"""Voice-over for the vertical short via ElevenLabs, with word timings for captions and the cut.

    uv run --with requests short/vo.py en [model]   -> ~/.cache/hoardi-demo/short/vo-en.mp3 + vo-en.json

The JSON holds every word (start/end seconds) and every beat (shot, start, end), taken from the
character alignment of one request for the whole script, so the delivery stays one natural take.
Key: ~/.config/elevenlabs/hoardi-key (never in the repo).
"""
import base64
import json
import sys
from pathlib import Path

import requests

HERE = Path(__file__).parent
OUT = Path.home() / ".cache/hoardi-demo/short"
KEY = (Path.home() / ".config/elevenlabs/hoardi-key").read_text().strip()
API = "https://api.elevenlabs.io/v1"

# what the voice should say instead of the written word (captions keep the written form)
SAY = {"de": {"Hoardi": "Hordi"}, "en": {}}


def main():
    lang = sys.argv[1]
    model = sys.argv[2] if len(sys.argv) > 2 else "eleven_v3"
    script = json.loads((HERE / "script.json").read_text())
    beats = script["beats"]

    spoken, offsets = "", []
    for beat in beats:
        line = beat[lang]
        for written, said in SAY[lang].items():
            line = line.replace(written, said)
        if spoken:
            spoken += " "
        offsets.append((len(spoken), len(spoken) + len(line)))
        spoken += line

    r = requests.post(
        f"{API}/text-to-speech/{script['voices'][lang]}/with-timestamps",
        headers={"xi-api-key": KEY},
        params={"output_format": "mp3_44100_192"},
        json={"text": spoken, "model_id": model, "language_code": lang},
        timeout=180,
    )
    r.raise_for_status()
    data = r.json()
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f"vo-{lang}.mp3").write_bytes(base64.b64decode(data["audio_base64"]))

    al = data["alignment"]
    chars, starts, ends = al["characters"], al["character_start_times_seconds"], al["character_end_times_seconds"]
    if "".join(chars) != spoken:
        sys.exit("alignment text differs from the request text")

    back = {said: written for written, said in SAY[lang].items()}
    words, i = [], 0
    while i < len(chars):
        if chars[i].isspace():
            i += 1
            continue
        j = i
        while j < len(chars) and not chars[j].isspace():
            j += 1
        text = "".join(chars[i:j])
        for said, written in back.items():
            text = text.replace(said, written)
        words.append({"text": text, "start": starts[i], "end": ends[j - 1], "char": i})
        i = j

    out_beats = []
    for beat, (a, b) in zip(beats, offsets):
        out_beats.append({"shot": beat["shot"], "text": beat[lang], "start": starts[a], "end": ends[b - 1],
                          "words": [k for k, w in enumerate(words) if a <= w["char"] < b]})
    (OUT / f"vo-{lang}.json").write_text(json.dumps({"model": model, "words": words, "beats": out_beats}, indent=1))
    for b in out_beats:
        print(f"{b['start']:6.2f}-{b['end']:6.2f}  {b['shot']:9s} {b['text']}")
    print(f"total {ends[-1]:.2f}s -> {OUT / f'vo-{lang}.mp3'}")


if __name__ == "__main__":
    main()

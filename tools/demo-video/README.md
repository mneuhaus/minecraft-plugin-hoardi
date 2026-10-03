# Demo video pipeline

Films the Hoardi demo on the local test server (`test/`, Paper 26.2, port 25566) with a real vanilla client.

| Piece | What it does |
|---|---|
| `director/` | Test-server-only plugin `HoardiDemo`: `/demo build` stages the storage hall at 1000 -60 1000, `/demo reset` restores the opening state, `/demo cam <shot> <sec>` poses a still, `/demo all` flies the spectator camera (an item display with teleport interpolation) through every shot and logs shot marks to `plugins/HoardiDemo/marks.log`. |
| `launch-client.sh` | Vanilla 26.2 via portablemc, offline name `HoardiCam`, 960x540 window (1080p framebuffer on Retina). Client data lives in `~/.cache/hoardi-demo`. |
| `restart.sh` | Restarts the test server and reconnects the client (Paper has no `/reload`). |
| `wincap.swift` | ScreenCaptureKit recorder for the Minecraft window only (`shot` / `record`), writes the first-frame wall clock next to the movie. |
| `take.sh` | One take: reset, record, run the shots, stop on the director's `done` mark. |
| `edit.py` | Cuts the take per shot, adds captions/title cards (Inter, OFL) and crossfades -> `out/hoardi-demo.mp4`. |
| `clean-pack/` | Resource pack without crosshair (currently not picked up by the client; spectator view shows none anyway). |

Setup clip (three layouts + one colour per network): `demo setupbuild` once, then `./take.sh s1 setup` and
`uv run --with pillow edit.py s1 out/hoardi-setup.mp4 setup`.

Never relaunch the client while Marc's own Minecraft is open (it steals focus); `take.sh` pins the recorder to
the demo client's PID.

Compatibility: `tools/compat/smoke.sh <paper-version>` runs the same director as a test driver against a
throwaway Paper container (checks network build, sort, shelf previews, barrels, separate wood networks, log).

```bash
cd tools/demo-video
director/build.sh && ./restart.sh
docker exec paper-test rcon-cli "demo build"
./take.sh t1                      # keep the Minecraft window visible: macOS throttles covered windows
uv run --with pillow edit.py t1 out/hoardi-demo.mp4
```

Client options that matter (`~/.cache/hoardi-demo/work/options.txt`): `pauseOnLostFocus:false`, `chatVisibility:2`, `gamma:1.0`, `soundCategory_master:0.0`, `onboardAccessibility:false`.

## Vertical short (YouTube Shorts, EN + DE)

`short/` cuts a 1080x1920 short to an ElevenLabs voice-over (key in `~/.config/elevenlabs/hoardi-key`, never in the repo).
Lines, hook title and end card text live in `short/script.json`; the director's `v*` shots are timed so `short/edit.py`
can anchor the lid closing and the first shelf to the spoken word.

```bash
RES=540x960 ./restart.sh                          # portrait client (1080x1920 framebuffer on Retina)
docker exec paper-test rcon-cli "demo build"
SIZE="1080 1920" WINCAP_ASPECT=9:16 ./take.sh v3 vdump vshelves vfind vgrow vend
ROOM=setup SIZE="1080 1920" WINCAP_ASPECT=9:16 ./take.sh vs2 vsetup vnet
uv run --with requests short/vo.py en             # voice-over + word timings -> ~/.cache/hoardi-demo/short
uv run --with pillow --with numpy short/edit.py en v3 vs2   # -> out/hoardi-short-en.mp4
```

Music (`music.mp3`, ElevenLabs Music), `sfx-*.mp3` (ElevenLabs sound effects) and `mc-*.ogg` (copied from the client's
asset index) are expected in `~/.cache/hoardi-demo/short`.

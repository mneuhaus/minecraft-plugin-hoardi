# YouTube uploads (Marc's personal channel)

API uploads from an unverified Google Cloud project are locked to private, so uploads go through YouTube Studio in a
dedicated Chrome profile, driven over raw CDP (Playwright's connect_over_cdp hangs on Chrome 154's browser_ui targets).

```bash
# once per session: the Studio Chrome (own profile, logged in already; the flags stop Chrome from freezing it when covered)
open -na "Google Chrome" --args --user-data-dir="$HOME/.cache/yt-studio/profile" --remote-debugging-port=9333 \
  --no-first-run --no-default-browser-check --disable-backgrounding-occluded-windows --disable-renderer-backgrounding \
  --disable-background-timer-throttling --disable-features=CalculateNativeWinOcclusion,IntensiveWakeUpThrottling \
  "https://studio.youtube.com"

node tools/youtube/studio-upload.mjs tools/youtube/shorts-2026-10-03.json hoardi-en          # fill only, screenshot
node tools/youtube/studio-upload.mjs tools/youtube/shorts-2026-10-03.json hoardi-en --go     # publish publicly
```

Each entry: `file`, `title`, `desc` (newlines kept). The script sets "not made for kids", no paid promotion, AI use "No"
(Studio's own criteria: real person, real event, realistic scene, music at the centre; gameplay with a narrator and a
background track meets none), visibility Public, then prints the Shorts link. Screenshots land in the temp dir.

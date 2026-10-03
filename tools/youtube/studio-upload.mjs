// Publishes one Short through the logged-in YouTube Studio tab, talking raw CDP to that tab only.
//   node youtube/studio-upload.mjs <meta.json> <key> [--draft "<title text in the Shorts list>"] [--go]
// Needs the Studio Chrome from youtube/README.md (own profile, CDP port 9333, anti-freeze flags).
import fs from 'node:fs';
import os from 'node:os';

const [metaFile, key, ...rest] = process.argv.slice(2);
const draft = rest.includes('--draft') ? rest[rest.indexOf('--draft') + 1] : null;
const go = rest.includes('--go');
const meta = JSON.parse(fs.readFileSync(metaFile, 'utf8'))[key];
const CH = 'https://studio.youtube.com/channel/UC-oHa3Mnk8v0Gf_UsFaI6Hg';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const targets = await (await fetch('http://127.0.0.1:9333/json/list')).json();
const tab = targets.find((t) => t.type === 'page' && t.url.includes('studio.youtube.com'));
const ws = new WebSocket(tab.webSocketDebuggerUrl);
await new Promise((r) => ws.addEventListener('open', r, { once: true }));
let id = 0;
const pending = new Map();
ws.addEventListener('message', (ev) => {
  const msg = JSON.parse(ev.data);
  if (msg.id && pending.has(msg.id)) {
    pending.get(msg.id)(msg);
    pending.delete(msg.id);
  }
});
const send = (method, params = {}) => new Promise((resolve, reject) => {
  const n = ++id;
  pending.set(n, (m) => (m.error ? reject(new Error(`${method}: ${m.error.message}`)) : resolve(m.result)));
  ws.send(JSON.stringify({ id: n, method, params }));
});
const js = async (expr) => {
  const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true });
  if (r.exceptionDetails) throw new Error(`js: ${r.exceptionDetails.text} ${expr.slice(0, 80)}`);
  return r.result.value;
};
const shot = async (name) => {
  const r = await send('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(`${os.tmpdir()}/yt-${key}-${name}.png`, Buffer.from(r.data, 'base64'));
};
const waitFor = async (selector, ms = 60000) => {
  for (let t = 0; t < ms; t += 500) {
    if (await js(`!!document.querySelector(${JSON.stringify(selector)})`)) return;
    await sleep(500);
  }
  await shot('timeout');
  throw new Error(`timeout waiting for ${selector}`);
};
// a real mouse click at the element's centre (Polymer buttons ignore synthetic .click() now and then)
const click = async (selector) => {
  const box = await js(`(() => { const e = document.querySelector(${JSON.stringify(selector)}); if (!e) return null;
    e.scrollIntoView({block: 'center'}); const r = e.getBoundingClientRect(); return {x: r.x + r.width / 2, y: r.y + r.height / 2}; })()`);
  if (!box) throw new Error(`no element ${selector}`);
  await sleep(150);
  for (const type of ['mouseMoved', 'mousePressed', 'mouseReleased']) {
    await send('Input.dispatchMouseEvent', { type, x: box.x, y: box.y, button: 'left', clickCount: 1 });
  }
  await sleep(300);
};
const key_ = (k, code, vk, text) => ['keyDown', 'keyUp'].reduce((p, type) => p.then(() =>
  send('Input.dispatchKeyEvent', { type, key: k, code, windowsVirtualKeyCode: vk, ...(type === 'keyDown' && text ? { text } : {}) })), Promise.resolve());
const replaceText = async (selector, text) => {
  await click(selector);
  await send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'a', code: 'KeyA', windowsVirtualKeyCode: 65, modifiers: 4, commands: ['selectAll'] });
  await send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'a', code: 'KeyA', windowsVirtualKeyCode: 65, modifiers: 4 });
  await key_('Backspace', 'Backspace', 8);
  const lines = text.split('\n');
  for (let i = 0; i < lines.length; i++) {
    if (i) await key_('Enter', 'Enter', 13, '\r');
    if (lines[i]) await send('Input.insertText', { text: lines[i] });
  }
};

await send('Page.enable');
await send('DOM.enable');
await send('Page.bringToFront');
if (draft) {
  await send('Page.navigate', { url: `${CH}/videos/short` });
  await sleep(5000);
  const opened = await js(`(() => { const row = [...document.querySelectorAll('ytcp-video-row')].find(r => r.innerText.includes(${JSON.stringify(draft)}));
    if (!row) return 'no row'; const b = [...row.querySelectorAll('ytcp-button, button, a')].find(b => /Entwurf bearbeiten|Edit draft/.test(b.innerText)); if (!b) return 'no button'; b.click(); return 'ok'; })()`);
  if (opened !== 'ok') throw new Error(`draft: ${opened}`);
} else {
  await send('Page.navigate', { url: `${CH}/videos/upload?d=ud` });
  await waitFor('input[type=file]', 30000);
  const { root } = await send('DOM.getDocument', { depth: 0 });
  const { nodeId } = await send('DOM.querySelector', { nodeId: root.nodeId, selector: 'input[type=file]' });
  await send('DOM.setFileInputFiles', { nodeId, files: [meta.file.replace('~', os.homedir())] });
}
await waitFor('#title-textarea #textbox');
await sleep(2500);
await replaceText('#title-textarea #textbox', meta.title);
await replaceText('#description-textarea #textbox', meta.desc);
await click('tp-yt-paper-radio-button[name="VIDEO_MADE_FOR_KIDS_NOT_MFK"]');
if (/mehr|more/i.test(await js(`document.querySelector('#toggle-button')?.innerText || ''`))) {
  await click('#toggle-button');
  await sleep(1200);
}
await click('tp-yt-paper-radio-button[name="VIDEO_PAID_PRODUCT_PLACEMENT_NO"]');
await click('tp-yt-paper-radio-button[name="VIDEO_HAS_ALTERED_CONTENT_NO"]');
const state = await js(`({
  title: document.querySelector('#title-textarea #textbox').innerText,
  desc: document.querySelector('#description-textarea #textbox').innerText.split('\\n').length,
  radios: [...document.querySelectorAll('tp-yt-paper-radio-button')].filter(r => r.getAttribute('aria-checked') === 'true').map(r => r.getAttribute('name')),
  link: ([...document.querySelectorAll('a')].find(a => a.href.includes('/shorts/')) || {}).href || null })`);
console.log(JSON.stringify(state));
await shot('details');
if (!go) process.exit(0);

for (let i = 0; i < 3; i++) {
  await click('#next-button');
  await sleep(2000);
}
await click('tp-yt-paper-radio-button[name="PUBLIC"]');
await sleep(800);
await shot('visibility');
for (let t = 0; t < 360; t++) {
  const s = await js(`document.querySelector('ytcp-video-upload-progress')?.innerText || ''`);
  if (!/\d+\s?%|Uploading|wird hochgeladen/i.test(s)) break;
  await sleep(1000);
}
await click('#done-button');
await sleep(6000);
await shot('published');
const done = await js(`({ dialog: (document.querySelector('ytcp-video-share-dialog, ytcp-uploads-still-processing-dialog, ytcp-prechecks-warning-dialog') || {}).innerText?.replace(/\\s+/g, ' ').slice(0, 200) || null,
  link: ([...document.querySelectorAll('ytcp-video-share-dialog a, a')].find(a => a.href.includes('/shorts/') || a.href.includes('youtu.be/')) || {}).href || null })`);
console.log(JSON.stringify(done));
await js(`(() => { const b = document.querySelector('ytcp-video-share-dialog #close-button, ytcp-uploads-still-processing-dialog #close-button'); if (b) b.click(); })()`);
ws.close();

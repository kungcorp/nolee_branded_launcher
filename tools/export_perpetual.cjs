/**
 * Export the actual Watch_Face SVG, including browser lighting filters and fonts,
 * into small native drawing layers. No hand-maintained copy of the dial geometry.
 * Run with Node + playwright (Chrome installed), from any directory.
 * Generated assets are committed/source artifacts: Android builds need no browser.
 */
const { chromium } = require('playwright');
const fs = require('node:fs/promises');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const crypto = require('node:crypto');
const { panel, adaptScript, adaptSvg } = require('./perpetual-panel.cjs');

const root = path.resolve(__dirname, '..');
const source = path.resolve(root, '../Watch_Face');
const output = path.join(root, 'android/app/src/main/assets/perpetual');
const proof = path.join(root, '.temp/watch-parity');

(async () => {
  await fs.mkdir(output, { recursive: true });
  await fs.mkdir(proof, { recursive: true });
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 440, height: 540 }, deviceScaleFactor: 2 });
    // Freeze the source clock to make exported rotations and the visual proof reproducible.
    await page.addInitScript(() => {
      const NativeDate = Date;
      window.Date = class extends NativeDate {
        constructor(...args) { super(...(args.length ? args : [2026, 8, 16, 10, 9, 36, 0])); }
        static now() { return new NativeDate(2026, 8, 16, 10, 9, 36).getTime(); }
      };
      window.requestAnimationFrame = () => 0;
    });
    await page.route('**/js/watchface.js', route => route.fulfill({
      contentType: 'application/javascript', body: adaptScript(require('node:fs').readFileSync(path.join(source, 'js/watchface.js'), 'utf8')),
    }));
    await page.goto(pathToFileURL(path.join(source, 'index.html')).href);
    await page.evaluate(adaptSvg, panel);
    await page.evaluate(() => document.fonts.ready);
    const layers = await page.evaluate(() => {
      const svg = document.getElementById('face');
      const ns = svg.namespaceURI;
      const glowOnly = svg.querySelector('#tealGlow').cloneNode(true);
      glowOnly.id = 'tealGlowOnly';
      glowOnly.querySelector('feMergeNode[in="SourceGraphic"]').remove();
      svg.querySelector('defs').appendChild(glowOnly);
      const defs = svg.querySelector('defs').outerHTML;
      const style = '<style>text{font-family:"Segoe UI Semibold","Helvetica Neue",Arial,sans-serif}</style>';
      const wrap = (body, b) => `<svg xmlns="${ns}" width="${b.width}" height="${b.height}" viewBox="${b.x} ${b.y} ${b.width} ${b.height}" style="--ground:#030504">${defs}${style}${body}</svg>`;
      const result = {};
      function take(name, node, bounds) {
        const b = bounds || node.getBBox();
        const box = bounds || { x: Math.floor(b.x - 12), y: Math.floor(b.y - 12), width: Math.ceil(b.width + 24), height: Math.ceil(b.height + 24) };
        result[name] = { ...box, svg: wrap(node.outerHTML, box) };
      }
      // Store hands pointing up in their own local coordinates. The app rotates
      // these exact shapes, gradients and glows around the original SVG pivots.
      for (const [name, selector] of [
        ['hour', '#hourHand'], ['minute', '#minuteHand'], ['second', '#secondHand'],
        ['day', '#sub-day > path'], ['h24', '#sub-day > path:nth-of-type(2)'],
        ['date', '#sub-date > path'], ['month', '#sub-month > path'],
      ]) {
        const node = svg.querySelector(selector);
        node.removeAttribute('transform');
        take(name, node);
        if (name === 'hour' || name === 'minute') {
          // Native vectors use the actual path and gradient stops, avoiding
          // resampling the hand's color/sharp outline with a rotated bitmap.
          result[name].path = node.getAttribute('d');
          result[name].colors = [...svg.querySelectorAll('#handFill stop')].map(s => s.getAttribute('stop-color'));
          result[name].stroke = node.getAttribute('stroke');
          result[name].strokeWidth = +node.getAttribute('stroke-width');
          const glow = node.cloneNode(true);
          glow.setAttribute('filter', 'url(#tealGlowOnly)');
          const { x, y, width, height } = result[name];
          take(name + 'Glow', glow, { x, y, width, height });
        }
      }
      const hub = svg.querySelector('#capGroup');
      hub.prepend(svg.querySelector('#handsGroup > circle').cloneNode(true));
      take('hub', hub);
      const caps = document.createElementNS(ns, 'g');
      for (const id of ['day', 'date', 'month']) {
        const dial = svg.querySelector('#sub-' + id);
        const cap = dial.lastElementChild;
        caps.appendChild(cap.cloneNode(true));
        cap.remove();
      }
      svg.appendChild(caps);
      take('subcaps', caps);
      caps.remove();
      // Keep every piece of the moon aperture, including the overlapping discs,
      // stars, glows and bevel. Phase frames below are rendered by the same SVG.
      const moon = svg.querySelector('#moonDisc');
      const moonAssembly = document.createElementNS(ns, 'g');
      moonAssembly.appendChild(moon.cloneNode(true));
      // Aperture bevels sit above the moving sky in the source paint order.
      for (let rim = moon.nextElementSibling; rim; rim = rim.nextElementSibling) {
        moonAssembly.appendChild(rim.cloneNode(true));
      }
      const moonBox = { x: 172, y: 102, width: 96, height: 96 };
      take('moon', moonAssembly, moonBox);
      const year = svg.querySelector('#yearText');
      for (let n = 0; n < 10; n++) {
        year.textContent = String(n);
        year.setAttribute('x', '0');
        year.setAttribute('y', '0');
        year.setAttribute('text-anchor', 'start');
        take('digit' + n, year, { x: 0, y: -16, width: 12, height: 20 });
        result['digit' + n].advance = year.getComputedTextLength();
      }
      year.remove();
      for (const sel of ['#handsGroup', '#capGroup', '#sub-day > path', '#sub-date > path', '#sub-month > path', '#moonDisc']) {
        svg.querySelectorAll(sel).forEach(n => n.remove());
      }
      take('base', svg, { x: 0, y: 0, width: 440, height: 540 });
      // Avoid a nested viewport when rendering the full dial.
      result.base.svg = wrap(svg.innerHTML.replace(/<defs>[\s\S]*?<\/defs>/, ''), result.base);
      return result;
    });

    const render = await browser.newPage({ deviceScaleFactor: 2 });
    const manifest = { source: 'Watch_Face/index.html + js/watchface.js', scale: 2, panel, layers: {} };
    for (const [name, layer] of Object.entries(layers)) {
      if (name === 'moon') continue;
      await render.setViewportSize({ width: layer.width, height: layer.height });
      await render.setContent(`<style>html,body{margin:0;background:transparent}svg{display:block}</style>${layer.svg}`);
      await render.screenshot({ path: path.join(output, name + '.png'), omitBackground: true });
      const { svg, ...bounds } = layer;
      manifest.layers[name] = bounds;
    }

    // Render small lettering, reflective markers and the bezel directly to the
    // panel's pixel grid. The native app copies this opaque image 1:1 at rest.
    const panelPage = await browser.newPage({ viewport: { width: panel.width, height: panel.height }, deviceScaleFactor: 1 });
    const baseSvg = layers.base.svg.replace(/width="440" height="540" viewBox="0 0 440 540"/, `width="408" height="502" viewBox="${panel.left} ${panel.top} ${408 / panel.scale} ${502 / panel.scale}"`);
    // A composited SVG uses grayscale text AA: no baked RGB subpixel fringes
    // that would assume the watch has the desktop monitor's subpixel order.
    await panelPage.setContent(`<style>html,body{margin:0;background:#030504}svg{display:block;opacity:.9999}</style>${baseSvg}`);
    await panelPage.screenshot({ path: path.join(output, 'panel.png') });
    manifest.layers.panel = { x: panel.left, y: panel.top, width: 408 / panel.scale, height: 502 / panel.scale };

    // 256 phases differ by < 0.4 pixel at the display size. One 1536² atlas
    // holds the complete aperture, avoiding runtime SVG filters / WebView work.
    const moon = layers.moon;
    const phasePage = await browser.newPage({ viewport: { width: 1536, height: 1536 }, deviceScaleFactor: 1 });
    const shape = (r, phase) => {
      const rx = r * Math.abs(Math.cos(2 * Math.PI * phase));
      const outer = phase <= .5 ? 1 : 0;
      const inner = phase <= .5 ? (phase < .25 ? 1 : 0) : (phase < .75 ? 0 : 1);
      return `M 0 ${-r} A ${r} ${r} 0 0 ${outer} 0 ${r} A ${rx} ${r} 0 0 ${inner} 0 ${-r} Z`;
    };
    await phasePage.setContent('<style>html,body{margin:0;background:transparent}body{display:grid;grid-template-columns:repeat(16,96px)}svg{display:block}</style>');
    await phasePage.evaluate(({ markup }) => {
      const holder = document.createElement('div');
      holder.innerHTML = markup;
      for (let i = 0; i < 256; i++) {
        const svg = holder.firstElementChild.cloneNode(true);
        // IDs must be unique because all phase cells share the same document.
        svg.innerHTML = svg.innerHTML.replace(/id="([^"]+)"/g, (_, id) => `id="${id}-${i}"`).replace(/url\(#([^\)]+)\)/g, (_, id) => `url(#${id}-${i})`);
        document.body.appendChild(svg);
      }
    }, { markup: moon.svg });
    const phases = Array.from({ length: 256 }, (_, i) => [shape(71 * .66 * .54 - 3, i / 256), shape(71 * .66 * .54 - 3, 1 - i / 256)]);
    await phasePage.evaluate(phases => {
      [...document.body.children].forEach((svg, i) => {
        const paths = svg.querySelectorAll('g[id^="moonDisc"] > g > path');
        paths.forEach((p, j) => p.setAttribute('d', phases[i][j]));
      });
    }, phases);
    await phasePage.screenshot({ path: path.join(output, 'moons.png'), omitBackground: true });
    manifest.moon = { x: moon.x, y: moon.y, cell: 96, columns: 16, frames: 256 };
    const hash = crypto.createHash('sha256');
    for (const file of ['index.html', 'css/style.css', 'js/watchface.js']) hash.update(await fs.readFile(path.join(source, file)));
    manifest.sourceSha256 = hash.digest('hex');
    await fs.writeFile(path.join(output, 'layers.json'), JSON.stringify(manifest, null, 2) + '\n');
    console.log(`Exported ${Object.keys(manifest.layers).length} SVG layers and moon atlas to ${output}`);
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });

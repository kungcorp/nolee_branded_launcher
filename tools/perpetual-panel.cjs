// Display adaptation of the original SVG. Keep palette/lighting definitions in
// Watch_Face; only lens geometry and grain visibility change for this panel.
const panel = { width: 408, height: 502, inset: 2, radius: 112, scale: 404 / 412, grainContrast: 1.6, metalGamma: 1.6 };
panel.left = 220 - panel.width / 2 / panel.scale;
panel.top = 270 - panel.height / 2 / panel.scale;
panel.cornerDelta = panel.radius / panel.scale - 104;
panel.heightInset = 256 - (panel.height / 2 - panel.inset) / panel.scale;

function adaptScript(source) {
  return source.replace(
    'function squirclePoint(angleDeg, W, H, r) {',
    `function squirclePoint(angleDeg, W, H, r) {
      if (W >= 160 && H >= 202) { H -= ${panel.heightInset}; r += ${panel.cornerDelta}; }`,
  );
}

// Passed to page.evaluate; no dependencies outside its argument.
function adaptSvg(panel) {
  const svg = document.getElementById('face');
  // Preserve the original material hues and white specular glints, while
  // deepening the midtones so the bevel reads on the tiny illuminated panel.
  const transfer = document.createElementNS(svg.namespaceURI, 'feComponentTransfer');
  for (const channel of ['R', 'G', 'B']) {
    const fn = document.createElementNS(svg.namespaceURI, 'feFunc' + channel);
    fn.setAttribute('type', 'gamma');
    fn.setAttribute('exponent', panel.metalGamma);
    transfer.appendChild(fn);
  }
  svg.querySelector('#raisedGlyph').appendChild(transfer);
  svg.querySelectorAll('#caseGroup > rect, #dialGroup > rect, #dialClip > rect').forEach(rect => {
    rect.setAttribute('y', +rect.getAttribute('y') + panel.heightInset);
    rect.setAttribute('height', +rect.getAttribute('height') - 2 * panel.heightInset);
    rect.setAttribute('rx', +rect.getAttribute('rx') + panel.cornerDelta);
    rect.setAttribute('ry', rect.getAttribute('rx'));
    if (+rect.getAttribute('x') === 14 && rect.getAttribute('fill') === 'none') {
      // Centre the rim stroke inside the measured silhouette, not across it.
      const halfStroke = +rect.getAttribute('stroke-width') / 2;
      for (const axis of ['x', 'y']) rect.setAttribute(axis, +rect.getAttribute(axis) + halfStroke);
      for (const axis of ['width', 'height']) rect.setAttribute(axis, +rect.getAttribute(axis) - 2 * halfStroke);
      for (const axis of ['rx', 'ry']) rect.setAttribute(axis, +rect.getAttribute(axis) - halfStroke);
    }
  });
  for (const id of ['dialClip', 'clip-moon', 'clip-day', 'clip-date', 'clip-month']) {
    svg.querySelectorAll(`g[clip-path="url(#${id})"] > circle`).forEach(grain => {
      grain.setAttribute('opacity', +grain.getAttribute('opacity') * panel.grainContrast);
    });
  }
  // Small legends need whole-pixel baselines and at least 8 physical pixels.
  // Positions move by at most half a pixel; keep the reference's text anchors.
  svg.querySelectorAll('text').forEach(text => {
    const size = +text.getAttribute('font-size');
    if (size > 0 && size <= 14) {
      const footer = text.parentElement.id === 'brandGroup' && text.id !== 'yearText';
      text.setAttribute('font-size', Math.max(footer ? 11 : 8, Math.round(size * panel.scale)) / panel.scale);
      if (text.hasAttribute('x') && text.hasAttribute('y') && text.parentElement.parentElement === svg) {
        const x = +text.getAttribute('x'), y = +text.getAttribute('y');
        text.setAttribute('x', Math.round((x - panel.left) * panel.scale) / panel.scale + panel.left);
        text.setAttribute('y', Math.round((y - panel.top) * panel.scale) / panel.scale + panel.top);
      }
    }
  });
}

module.exports = { panel, adaptScript, adaptSvg };

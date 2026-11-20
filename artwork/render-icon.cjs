// Render the same original SVG used by Android vector resources; no network.
const { Resvg } = require('../tests/runtime/node_modules/@resvg/resvg-js');
const fs = require('node:fs');
const path = require('node:path');
const svg = fs.readFileSync(path.join(__dirname, 'icon.svg'), 'utf8');
for (const width of [512, 1024]) {
  const image = new Resvg(svg, { fitTo: { mode: 'width', value: width } }).render().asPng();
  const out = path.join(__dirname, `inkstone-icon-${width}.png`);
  if (fs.existsSync(out)) {
    if (!fs.readFileSync(out).equals(image)) throw new Error('Refusing to overwrite changed icon preview: ' + out);
  } else fs.writeFileSync(out, image, { flag: 'wx' });
  console.log(out, image.length, 'bytes');
}

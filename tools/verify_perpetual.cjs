/** Fixed-time visual comparison: source SVG vs the exported native layers. */
const { chromium } = require('playwright');
const fs = require('node:fs/promises');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const { panel, adaptScript, adaptSvg } = require('./perpetual-panel.cjs');
const root = path.resolve(__dirname, '..');
const proof = path.join(root, '.temp/watch-parity');
(async () => {
 await fs.mkdir(proof, { recursive: true });
 const browser = await chromium.launch({channel:'chrome',headless:true});
 try {
 const fixed = process.argv[2] || '2026-09-16T16:25:43.000+08:00';
 const page = await browser.newPage({viewport:{width:408,height:502},deviceScaleFactor:1,timezoneId:'Asia/Hong_Kong'});
 await page.addInitScript(fixed => {
   const NativeDate=Date;
   window.Date=class extends NativeDate {constructor(...args){super(...(args.length?args:[fixed]));} static now(){return new NativeDate(fixed).getTime();}};
   window.requestAnimationFrame=fn=>{window.drawTick=fn;return 0;};
 },fixed);
 await page.goto(pathToFileURL(path.resolve(root,'../Watch_Face/index.html')).href);
 await page.evaluate(()=>{
  window.drawTick();
  const s=Math.max(404/412,498/512);
  document.querySelector('.hint').remove();
  const css=document.createElement('style');
  css.textContent=`html,body{display:block;width:408px;height:502px;overflow:hidden;background:#030504}.stage{display:block;padding:0}#face{position:absolute;width:${440*s}px;height:${540*s}px;left:${204-220*s}px;top:${251-270*s}px;filter:none}`;
  document.head.appendChild(css);
 });
 await page.screenshot({path:path.join(proof,'reference.png')});
 const sourceCss = await page.evaluate(() => document.head.lastElementChild.textContent);
 await page.route('**/js/watchface.js', route => route.fulfill({ contentType: 'application/javascript', body: adaptScript(require('node:fs').readFileSync(path.resolve(root,'../Watch_Face/js/watchface.js'),'utf8')) }));
 await page.reload();
 await page.evaluate(adaptSvg, panel);
 await page.evaluate(css => { window.drawTick(); document.querySelector('.hint').remove(); const style=document.createElement('style');style.textContent=css;document.head.appendChild(style); }, sourceCss);
 // Test the actual adapted SVG, including the outer edge of its rim stroke.
 const geometry = await page.evaluate(() => [...document.querySelectorAll('#caseGroup > rect')].slice(0,2).map(r => Object.fromEntries(['x','y','width','height','rx','stroke-width'].map(k => [k,+r.getAttribute(k)]))));
 for (const r of geometry) {
   const half=(r['stroke-width']||0)/2;
   assert.ok(Math.abs((r.x-half-panel.left)*panel.scale-2)<1e-6);
   assert.ok(Math.abs((r.y-half-panel.top)*panel.scale-2)<1e-6);
   assert.ok(Math.abs((r.rx+half)*panel.scale-112)<1e-6);
   assert.ok(Math.abs((r.y+r.height+half-panel.top)*panel.scale-500)<1e-6);
 }
 await page.screenshot({path:path.join(proof,'adapted-reference.png')});
 const assets=path.join(root,'android/app/src/main/assets/perpetual');
 const manifest=JSON.parse(await fs.readFile(path.join(assets,'layers.json'),'utf8'));
 const hash=crypto.createHash('sha256');
 for(const file of ['index.html','css/style.css','js/watchface.js']) hash.update(await fs.readFile(path.resolve(root,'../Watch_Face',file)));
 if(hash.digest('hex')!==manifest.sourceSha256) throw new Error('Watch_Face changed: regenerate assets with tools/export_perpetual.cjs.');
 const files={};
 for(const name of [...Object.keys(manifest.layers),'moons']) files[name]='data:image/png;base64,'+(await fs.readFile(path.join(assets,name+'.png'))).toString('base64');
 await page.setContent('<style>html,body{margin:0;background:#030504}</style><canvas width="408" height="502"></canvas>');
 await page.evaluate(async({manifest,files,fixed})=>{
  const imgs={};
  await Promise.all(Object.entries(files).map(async([key,src])=>{const im=new Image();im.src=src;await im.decode();imgs[key]=im;}));
  const ctx=document.querySelector('canvas').getContext('2d');
  ctx.fillStyle='#030504';ctx.fillRect(0,0,408,502);
  const scale=Math.max(404/412,498/512);ctx.translate(204-220*scale,251-270*scale);ctx.scale(scale,scale);
  const layer=name=>{const b=manifest.layers[name];ctx.drawImage(imgs[name],b.x,b.y,b.width,b.height);};
  const hand=(name,x,y,a)=>{
   ctx.save();ctx.translate(x,y);ctx.rotate(a*Math.PI/180);
   if(name==='hour'||name==='minute') {
    layer(name+'Glow');
    const b=manifest.layers[name],w=name==='hour'?12.5:9;
    const gradient=ctx.createLinearGradient(-w,0,w,0);b.colors.forEach((c,i)=>gradient.addColorStop(i,c));
    const path=new Path2D(b.path);ctx.fillStyle=gradient;ctx.fill(path);ctx.strokeStyle=b.stroke;ctx.lineWidth=b.strokeWidth;ctx.lineJoin='round';ctx.stroke(path);
   } else layer(name);
   ctx.restore();
  };
  const now=new Date(fixed),s=now.getSeconds()+now.getMilliseconds()/1000,m=now.getMinutes()+s/60,h=now.getHours()+m/60,df=h/24;
  layer('panel');
  const syn=29.530588853,age=(now-Date.UTC(2000,0,6,18,14))/86400000;
  const phase=(((age%syn+syn)%syn+df)%syn)/syn,frame=Math.round(phase*256)%256;
  ctx.drawImage(imgs.moons,(frame%16)*96,Math.floor(frame/16)*96,96,96,172,102,96,96);
  hand('day',115,272,(now.getDay()+df)*360/7);hand('h24',115,272,h*15);
  hand('date',325,272,(now.getDate()-1+df)*360/31);
  const dim=new Date(now.getFullYear(),now.getMonth()+1,0).getDate();
  hand('month',220,394,(now.getMonth()+(now.getDate()-1+df)/dim)*30);layer('subcaps');
  const year=String(now.getFullYear()),width=[...year].reduce((v,d)=>v+manifest.layers['digit'+d].advance,0);
  ctx.save();ctx.translate(102-width/2,408);for(const d of year){layer('digit'+d);ctx.translate(manifest.layers['digit'+d].advance,0);}ctx.restore();
  hand('hour',220,270,(h%12)*30);hand('minute',220,270,m*6);hand('second',220,270,s*6);layer('hub');
 },{manifest,files,fixed});
 await page.screenshot({path:path.join(proof,'reconstructed.png')});
 console.log('Saved original SVG and native-layer reconstruction at '+fixed);
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});

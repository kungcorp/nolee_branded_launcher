const view=document.querySelector('#view'),device=document.querySelector('#device');
// Inside the APK the native bridge exists: show only the watch surface, full screen.
const onDevice=!!window.NoleeNative;
if(onDevice)document.documentElement.classList.add('on-device');
// The watch WebView (Chrome 79) lacks @property but has CSS.registerProperty, which the peace mask reveal needs.
try{CSS.registerProperty({name:'--reveal',syntax:'<percentage>',inherits:false,initialValue:'100%'})}catch(error){}
const companions={Camera:'ai.nolee.camera',Files:'ai.nolee.files',Gallery:'ai.nolee.gallery',Phone:'ai.nolee.phone',SMS:'ai.nolee.sms'};
// Restart the website's peace reveal (bottom-up raster wipe plus mint aura) each time Home is entered.
const aiStar='<path d="M12 4C13.3 10.1 15.9 12.7 22 14C15.9 15.3 13.3 17.9 12 24C10.7 17.9 8.1 15.3 2 14C8.1 12.7 10.7 10.1 12 4ZM22.5 .5C23.125 3.875 24.125 4.875 27.5 5.5C24.125 6.125 23.125 7.125 22.5 10.5C21.875 7.125 20.875 6.125 17.5 5.5C20.875 4.875 21.875 3.875 22.5 .5Z"/>';
let homeEnter=true,homeEnterTimer=0;
// Entering Home (not re-rendering it) replays the artwork, the staggered entrance, the drum spin and the typewriter.
function startHomeEntrance(){
 greetingStart=performance.now();replayPeace();
 for(const el of [view,device]){el.classList.remove('home-entering');void el.offsetWidth;el.classList.add('home-entering')}
 clearTimeout(homeEnterTimer);homeEnterTimer=setTimeout(()=>{view.classList.remove('home-entering');device.classList.remove('home-entering')},1500);
}
// On Home the bottom bar is Voice Command; everywhere else it returns Home.
function syncHomeBar(){
 const bar=document.querySelector('#home'),mode=page==='Home'?'ask':'home';
 if(bar.dataset.mode===mode)return;
 bar.dataset.mode=mode;
 bar.innerHTML=mode==='ask'?`<span class="home-icon ai-star" aria-hidden="true"><svg viewBox="0 0 28 28">${aiStar}</svg></span><span class="home-label">VOICE COMMAND</span>`:'<span class="home-icon" aria-hidden="true">⌂</span><span class="home-label">HOME</span>';
 bar.setAttribute('aria-label',mode==='ask'?'Voice Command':'Return home');
 if(!matchMedia('(prefers-reduced-motion: reduce)').matches)[...bar.children].forEach((child,i)=>child.animate([{opacity:0,transform:'translateY(6px)'},{opacity:1,transform:'none'}],{duration:380,delay:i*50,easing:'cubic-bezier(.22,1,.36,1)',fill:'backwards'}));
}
function replayPeace(){const render=device.querySelector('.peace-render');render.classList.remove('is-replaying');void render.offsetWidth;render.classList.add('is-replaying')}
const icons={Camera:'<path d="M3 7h5l2-3h4l2 3h5v13H3Z"/><circle cx="12" cy="13" r="4"/>',Files:'<path d="M3 5h7l2 3h9v12H3Z"/>',Gallery:'<rect x="3" y="3" width="18" height="18" rx="1"/><circle cx="8" cy="8" r="2"/><path d="m4 19 6-7 4 4 3-3 4 6"/>',Phone:'<path d="m5 3 4 1 1 5-3 2c2 3 3 4 6 6l2-3 5 1 1 4c-1 4-7 2-12-3S1 4 5 3Z"/>'};
icons.SMS='<path d="M3 4h18v13H9l-6 4Z"/><path d="M7 8h10M7 12h7"/>';
icons.Time='<circle cx="12" cy="12" r="9"/><path d="M12 6v6l4 2"/>';
icons.System='<circle cx="12" cy="12" r="3"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1 7 17M17 7l2.1-2.1"/>';
let appPosition=0;
let page='Home',history=[],owner='Martin',number='',shots=0;
const settings={wifi:true,bluetooth:true,adaptive:true,haptics:true,kiosk:true,brightness:65,media:55,ring:70,scale:100,font:'Spline Mono'};
const escape=s=>s.replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
let pageAnimations=[];
function transitionTo(next){
 const animateReturn=page==='Time'&&next==='Home'&&!matchMedia('(prefers-reduced-motion: reduce)').matches&&typeof view.animate==='function';
 const face=animateReturn?view.querySelector('.watch-face'):null,outgoing=face?face.cloneNode(true):null;
 if(next==='Home'&&page!=='Home')homeEnter=true;
 page=next;render();
 if(!outgoing)return;
 // Freeze the old face as a visual layer; Home is already live underneath.
 outgoing.removeAttribute('aria-label');outgoing.setAttribute('aria-hidden','true');outgoing.inert=true;
 outgoing.querySelectorAll('[id]').forEach(el=>el.removeAttribute('id'));
 outgoing.classList.add('watch-exit-layer');
 view.append(outgoing);
 const exit=outgoing.animate([{opacity:1,transform:'translateY(0) scale(1)'},{opacity:0,transform:'translateY(-12px) scale(.94)'}],{duration:280,easing:'cubic-bezier(.22,1,.36,1)',fill:'forwards'});
 pageAnimations.push(exit);exit.onfinish=exit.oncancel=()=>outgoing.remove();
}
function go(next){
 // On the watch, companions open as their real apps; the preview screens remain the browser fallback.
 if(onDevice&&companions[next]&&NoleeNative.launch(companions[next]))return;
 history.push(page);transitionTo(next);
}
function back(){transitionTo(history.pop()||'Home')}
function home(){history=[];transitionTo('Home')}
function title(name,code='SYSTEM'){return `<div class="page-title"><h2>${name}</h2><span>${code}</span></div>`}
function row(name,detail,action,value='↗'){return `<button class="row" data-go="${action}"><span>${name}<small>${detail}</small></span><span class="value">${value}</span></button>`}
function toggle(label,key){return `<div class="row"><span>${label}</span><button class="toggle" data-toggle="${key}" aria-pressed="${settings[key]}">${settings[key]?'ON':'OFF'}</button></div>`}
function slider(label,key){return `<div class="setting"><label for="${key}">${label}<output id="${key}-out">${settings[key]}%</output></label><input id="${key}" data-slider="${key}" aria-label="${label}" type="range" min="${key==='scale'?85:0}" max="${key==='scale'?120:100}" value="${settings[key]}"></div>`}
function render(){
 pageAnimations.forEach(animation=>animation.cancel());pageAnimations=[];
 let content='';
 if(page==='Home')content=`<span class="tag">PERSONAL SYSTEM</span><h2 class="greeting home-greeting"><span class="typed" aria-hidden="true"></span><i class="cursor"></i></h2><div class="sub home-sub">Designed for Vibe Coders</div>${rollerMarkup()}<div class="session"><span><i class="session-dot">●</i> SESSION READY</span><span>${settings.kiosk?'KIOSK ACTIVE':'KIOSK OFF'}</span></div>`;
 else if(page==='Voice Command')content=title('Voice Command','VOICE / PREVIEW')+`<div class="scroll ask-ai"><span class="ask-orb" aria-hidden="true"><svg viewBox="0 0 28 28">${aiStar}</svg></span><p class="ask-prompt">What should I open or change?</p><div class="ask-suggestions">${['Open vitals','Show watch face','Turn Wi-Fi on'].map(s=>`<div class="row">${s}<span class="value">↗</span></div>`).join('')}</div><p class="notice">Simulated preview. The Android app recognizes supported commands offline.</p></div>`;
 else if(page==='Time')content=watchMarkup();
 else if(page==='System')content=title('System','CONTROL / 00')+`<div class="scroll">${row('Wi-Fi',settings.wifi?'Nolee Studio · connected':'Radio off','Wi-Fi')}${row('Bluetooth',settings.bluetooth?'Enabled · nearby devices':'Radio off','Bluetooth')}${row('Display','Brightness · text size','Display')}${row('Sound','Volume · haptics','Sound')}${row('Type system',settings.font,'Type system')}${row('Vitals','Battery · storage · device','Vitals')}${row('Launcher','Kiosk · companions · owner access','Launcher')}</div>`;
 else if(page==='Wi-Fi')content=title(page)+`<div class="scroll">${toggle('Wi-Fi','wifi')}${settings.wifi?row('Nolee Studio','Connected · strong signal','Network','●')+row('Guest network','Secured · available','Guest network'): '<p class="notice">Wi-Fi is off.</p>'}</div>`;
 else if(page==='Bluetooth')content=title(page)+`<div class="scroll">${toggle('Bluetooth','bluetooth')}${settings.bluetooth?row('Nearby speaker','Available to pair','Pair device'): '<p class="notice">Bluetooth is off.</p>'}</div>`;
 else if(page==='Display')content=title(page)+`<div class="scroll">${slider('Brightness','brightness')}${toggle('Adaptive brightness','adaptive')}${slider('Text size','scale')}<p class="notice">Values are simulated for this design preview.</p></div>`;
 else if(page==='Sound')content=title(page)+`<div class="scroll">${slider('Media volume','media')}${slider('Ring volume','ring')}${toggle('Touch haptics','haptics')}</div>`;
 else if(page==='Type system')content=title('Type system')+`<div class="scroll"><div class="row"><label for="font">Interface font</label><select id="font"><option${settings.font==='Spline Mono'?' selected':''}>Spline Mono</option><option${settings.font==='System Sans'?' selected':''}>System Sans</option></select></div><h2 class="greeting">Hello ${escape(owner)}</h2><p class="notice">Aa Bb Cc / 0123456789<br>Applied to this preview.</p></div>`;
 else if(page==='Vitals')content=title('Vitals','SAMPLE DATA')+`<div class="scroll"><div class="row">Battery <span class="value">84% · 31°C</span></div><div class="row">Storage <span class="value">18.4 / 32 GB</span></div><div class="row">Device <span class="value">Nolee Ultra</span></div><div class="row">Shell <span class="value">Peace / 01</span></div><p class="notice">Illustrative readings. The native build will read live device status.</p></div>`;
 else if(page==='Launcher')content=title('Launcher')+`<div class="scroll"><div class="row">Kiosk mode<span class="value">${settings.kiosk?'ACTIVE':'OFF'}</span></div>${row('Companion apps','Five native Nolee apps','Companions')}${row('Home destination','Peace launcher','Home destination')}${row(settings.kiosk?'Leave kiosk':'Enable kiosk','Owner access required','Owner access')}<p class="notice">System stays inside the shell. Owner access controls leaving the launcher environment.</p></div>`;
 else if(page==='Companions')content=title('Companions')+`<div class="scroll">${Object.keys(icons).filter(a=>!['Time','System'].includes(a)).map(a=>row(a,'ai.nolee.'+a.toLowerCase(),a,'READY')).join('')}</div>`;
 else if(page==='Owner access')content=title('Owner access')+`<p class="notice">On device, verify the owner before changing kiosk mode. This design preview simulates that result.</p><button class="call" id="verify">Simulate owner verification</button>`;
 else if(page==='Camera')content=title('Camera','NATIVE / PREVIEW')+`<div class="camera"><span>${shots?'PHOTO SAVED · DEMO':'PHOTO / 1× · DEMO VIEWFINDER'}</span></div><button class="capture" id="capture" aria-label="Take sample photo"></button>`;
 else if(page==='Files')content=title('Files','NATIVE / PREVIEW')+`<div class="scroll">${row('DCIM','Photos and videos','DCIM')}${row('Documents','Notes and documents','Documents')}${row('Downloads','Received files','Downloads')}<p class="notice">Sample content for native app handoff.</p></div>`;
 else if(page==='Gallery')content=title('Gallery','NATIVE / PREVIEW')+`<div class="gallery-grid">${['Peace artwork','Portrait study','Landscape study',shots?'New capture':'Boot artwork'].map(x=>`<button class="photo" data-go="Photo"><img src="assets/peace.png" alt=""><span>${x}</span></button>`).join('')}</div>`;
 else if(page==='Photo')content=title('Photo','SAMPLE')+`<div class="photo" style="height:230px"><img src="assets/peace.png" alt="Nolee peace artwork"></div>`;
 else if(page==='Phone')content=title('Phone','NATIVE / PREVIEW')+`<output class="number">${number||'Enter number'}</output><div class="dial">${'123456789*0#'.split('').map(x=>`<button data-digit="${x}">${x}</button>`).join('')}</div><button class="call" id="call">${number?'Call · demo':'Enter a number'}</button>`;
 else if(page==='SMS')content=title('SMS','NATIVE / PREVIEW')+`<div class="scroll">${row('Nolee Studio','Welcome to your personal system.','Message')}<p class="notice">Sample conversation. Messages open in the native Nolee SMS app on device.</p></div>`;
 else if(page==='Message')content=title('Nolee Studio','SMS / SAMPLE')+`<p class="notice">Welcome to your personal system.</p><p class="notice">Conversation preview only. No message is sent.</p>`;
 else if(page==='Calling')content=title('Phone','DEMO CALL')+`<h2 class="greeting">${escape(number)}</h2><p class="notice">Call preview. No real call is placed.</p><button class="call" id="end">End preview</button>`;
 else {const details={'Network':'Nolee Studio / Connected. Network details appear here in the native implementation.','Guest network':'Joining a secured network opens the native password flow. No network connection is changed in this preview.','Pair device':'Pairing confirmation appears here in the native implementation. No device is paired in this preview.','Home destination':'Peace launcher is the intended default Home destination. Set through the existing Nolee kiosk provisioning flow.','DCIM':'Camera / sample-photo.jpg','Documents':'welcome.txt / Sample document','Downloads':'No sample downloads in this design.'};content=title(page,'PREVIEW')+`<p class="notice">${details[page]||'Native app preview.'}</p>`}
 view.innerHTML=content;
 device.classList.toggle('watch-mode',page==='Time');
 device.classList.toggle('home-page',page==='Home');
 syncHomeBar();
 if(page==='Home'){if(homeEnter)startHomeEntrance();mountRoller();mountGreeting();homeEnter=false}
 else{view.classList.remove('home-entering');device.classList.remove('home-entering')}
 updateClock();
 view.querySelectorAll('[data-go]').forEach(b=>b.onclick=()=>go(b.dataset.go));
 view.querySelectorAll('[data-toggle]').forEach(b=>b.onclick=()=>{settings[b.dataset.toggle]=!settings[b.dataset.toggle];render()});
 view.querySelectorAll('[data-slider]').forEach(i=>i.oninput=()=>{settings[i.dataset.slider]=+i.value;document.querySelector('#'+i.dataset.slider+'-out').textContent=i.value+'%'});
 const font=document.querySelector('#font');if(font)font.onchange=()=>{settings.font=font.value;device.style.fontFamily=font.value==='System Sans'?'Arial,sans-serif':'Spline,monospace'};
 view.querySelectorAll('[data-digit]').forEach(b=>b.onclick=()=>{number=(number+b.dataset.digit).slice(0,16);render()});
 const actions={capture:()=>{shots++;render()},call:()=>{if(number)go('Calling')},end:()=>{page='Phone';history.pop();render()},verify:()=>{settings.kiosk=!settings.kiosk;page='Launcher';history.pop();render()}};
 Object.entries(actions).forEach(([id,fn])=>{const el=document.getElementById(id);if(el)el.onclick=fn});
}
document.querySelector('#home').onclick=()=>page==='Home'?go('Voice Command'):home();
document.querySelector('#owner').oninput=e=>{owner=e.target.value.trim()||'friend';if(page==='Home')render()};
document.querySelectorAll('[data-orient]').forEach(b=>b.onclick=()=>{device.classList.toggle('landscape',b.dataset.orient==='landscape');document.querySelectorAll('[data-orient]').forEach(x=>x.setAttribute('aria-pressed',x===b));document.querySelector('#dimension').innerHTML=(b.dataset.orient==='landscape'?'502 × 410 / LANDSCAPE':'410 × 502 / PORTRAIT')+' <span>ROUNDED DISPLAY SAFE AREA</span>';fit()});
document.addEventListener('keydown',e=>{if(['INPUT','SELECT'].includes(document.activeElement.tagName))return;if(e.key==='Escape')back();if(e.key==='Home')home()});
function fit(){
 const roller=view.querySelector('.app-roller');
 if(onDevice){
  // On the watch the device surface is the whole screen and orientation follows the display. The page is laid
  // out at the design width and the WebView page-scales it natively: a CSS-scaled device layer made every
  // animation re-raster through a transform on the watch's GPU.
  const land=innerWidth>innerHeight,w=land?502:410,h=land?410:502,viewport=`width=${w},user-scalable=no`,meta=document.querySelector('meta[name=viewport]');
  if(meta.getAttribute('content')!==viewport)meta.setAttribute('content',viewport);
  device.classList.toggle('landscape',land);
  let s=Math.min(innerWidth/w,innerHeight/h);if(Math.abs(s-1)<.02)s=1;
  device.style.transform=s===1?'':`scale(${s})`;device.style.left=Math.max(0,(innerWidth-w*s)/2)+'px';device.style.top=Math.max(0,(innerHeight-h*s)/2)+'px';
 }else{
  const w=device.classList.contains('landscape')?502:410,h=device.classList.contains('landscape')?410:502,s=Math.min(1,(innerWidth-58)/w);device.style.transform=`scale(${s})`;const stage=document.querySelector('.stage');stage.style.width=w*s+'px';device.style.marginBottom=-(h-h*s)+'px';
 }
 if(roller&&roller.refreshLayout)roller.refreshLayout();
}
addEventListener('resize',fit);render();fit();

setInterval(()=>{if(!document.hidden)updateClock()},1000);
document.addEventListener('visibilitychange',()=>{if(!document.hidden)updateClock()});

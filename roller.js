// A cylindrical selector, kept entirely inside the old app-grid footprint.
function rollerMarkup(){return `<section class="app-roller" aria-label="Apps" aria-roledescription="carousel"><div class="roller-window"><svg class="roller-connection" viewBox="-216 0 406 169" aria-hidden="true"><path d="M-6 148 H4 Q18 148 18 134 V99 Q18 84 33 84 H79"/><circle cx="79" cy="84" r="3"/></svg><div class="selection-frame" aria-hidden="true"></div>${Object.keys(icons).map((name,i)=>`<button class="roller-app" data-app-index="${i}" tabindex="-1"><svg viewBox="0 0 24 24" aria-hidden="true">${icons[name]}</svg><span><strong>${name}</strong><small>${name==='Time'?'WATCH FACE':name==='System'?'SETTINGS':'NATIVE APP'}</small></span><span class="launch-mark" aria-hidden="true">↗</span></button>`).join('')}</div><div class="roller-rail"><button class="roll-prev" aria-label="Previous app">⌃</button><output class="roll-count" aria-live="polite" aria-atomic="true"></output><button class="roll-next" aria-label="Next app">⌄</button></div></section>`}
function mountRoller(){
 const root=view.querySelector('.app-roller'),win=root.querySelector('.roller-window'),items=[...root.querySelectorAll('.roller-app')],names=Object.keys(icons),count=names.length;
 const reduced=matchMedia('(prefers-reduced-motion: reduce)').matches;
 const wrap=x=>((x%count)+count)%count,delta=x=>wrap(x+count/2)-count/2;
 // Entering Home spins the drum in from just above the remembered app.
 let tau=65,position=homeEnter&&!reduced?appPosition-1.6:appPosition,target=appPosition,frame=0,lastTime=0,drag=null,suppressClick=false,lastWheel=-Infinity;
 function paint(){
  const selected=wrap(Math.round(target));
  const landscape=device.classList.contains('landscape');
  items.forEach((button,i)=>{
   const d=delta(i-position),angle=d*.77,visible=Math.abs(d)<1.85;
   // The same card leaves the drum and travels into the lower-left launch slot.
   const dock=landscape?Math.pow(Math.max(0,1-Math.abs(d)),2):0;
   button.style.transform=`translate(${dock*-223}px,${Math.sin(angle)*78+dock*64}px) scale(${1-Math.min(Math.abs(d),2)*.1}) rotateX(${angle*-39}deg)`;
   button.style.width=landscape?`${147+dock*63}px`:'';
   button.style.opacity=Math.max(0,1-Math.abs(d)*.53);
   button.style.visibility=visible?'visible':'hidden';
   button.style.zIndex=String(10-Math.round(Math.abs(d)*3));
   button.classList.toggle('selected',i===selected);
   button.tabIndex=i===selected?0:-1;
   button.setAttribute('aria-label',i===selected?'Open '+names[i]:'Select '+names[i]);
   button.setAttribute('aria-current',i===selected?'true':'false');
  });
  const output=root.querySelector('.roll-count');
  const label=`${String(selected+1).padStart(2,'0')} / ${String(count).padStart(2,'0')}`;
  if(output.textContent!==label){output.textContent=label;output.setAttribute('aria-label',`${names[selected]}, ${selected+1} of ${count}`);if(!reduced)output.animate([{opacity:.2,transform:'scale(.7)'},{opacity:1,transform:'none'}],{duration:260,easing:'cubic-bezier(.22,1,.36,1)'})}
 }
 function animate(time){
  if(!root.isConnected){frame=0;return}
  const dt=Math.min(40,lastTime?time-lastTime:16);lastTime=time;
  position+=(target-position)*(1-Math.exp(-dt/tau));
  if(Math.abs(target-position)<.002){position=target;frame=0;lastTime=0;paint();return}
  paint();frame=requestAnimationFrame(animate);
 }
 function settle(next){
  target=Math.round(next);appPosition=target;tau=65;
  if(reduced){position=target;paint();return}
  if(!frame)frame=requestAnimationFrame(animate);
  paint();
 }
 function move(step){settle(target+step)}
 root.querySelector('.roll-prev').onclick=()=>move(-1);
 root.querySelector('.roll-next').onclick=()=>move(1);
 items.forEach((button,i)=>button.onclick=()=>{
  if(suppressClick){suppressClick=false;return}
  const distance=delta(i-target);
  if(distance===0&&Math.abs(position-target)<.1){
   if(reduced)return go(names[i]);
   if(root.dataset.launching)return;
   root.dataset.launching='1';button.classList.add('launching');
   setTimeout(()=>{if(root.isConnected)go(names[i])},150);
  }
  else settle(target+distance);
 });
 root.addEventListener('wheel',event=>{
  if(Math.abs(event.deltaY)<2)return;
  event.preventDefault();
  if(performance.now()-lastWheel<180)return;
  lastWheel=performance.now();move(Math.sign(event.deltaY));
 },{passive:false});
 root.addEventListener('keydown',event=>{
  if(['ArrowDown','ArrowRight','ArrowUp','ArrowLeft'].includes(event.key)){
   event.preventDefault();move(['ArrowDown','ArrowRight'].includes(event.key)?1:-1);
   items[wrap(target)].focus({preventScroll:true});
  }
 });
 win.addEventListener('pointerdown',event=>{
  if(event.button!==0)return;
  if(frame)cancelAnimationFrame(frame);frame=0;lastTime=0;
  suppressClick=false;drag={id:event.pointerId,y:event.clientY,start:position,moved:false};
 });
 win.addEventListener('pointermove',event=>{
  if(!drag||event.pointerId!==drag.id)return;
  const scale=device.getBoundingClientRect().width/device.offsetWidth;
  const dy=(drag.y-event.clientY)/scale;
  if(Math.abs(dy)>6&&!drag.moved){drag.moved=true;win.setPointerCapture(event.pointerId)}
  if(!drag.moved)return;
  position=drag.start+dy/56;target=position;paint();
 });
 function finish(event){
  if(!drag||event.pointerId!==drag.id)return;
  suppressClick=drag.moved;
  const next=drag.moved?position:appPosition;
  drag=null;settle(next);
 }
 win.addEventListener('pointerup',finish);
 win.addEventListener('pointercancel',finish);
 // A touch starts implicitly captured by the touched app button. Capturing to the window fires lostpointercapture on
 // that button, and it bubbles here: ending the drag then froze every touch swipe after its first few pixels.
 win.addEventListener('lostpointercapture',event=>{if(event.target===win)finish(event)});
 root.refreshLayout=paint;
 paint();
 if(position!==target){tau=170;frame=requestAnimationFrame(animate)}
}

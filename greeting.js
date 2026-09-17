// Home greeting: the Boot Art 05/06 typewriter, cycling through check-in phrases.
// Each phrase types, blinks its cursor five times, fades the cursor, holds, then erases.
const GREETING={delay:420,type:{portrait:145,landscape:83},erase:40,blink:500,blinks:5,fade:450,hold:2500,wake:250,gap:350};
let greetingStart=performance.now(),greetingLoop=0;
function greetingPhrases(){
 const hour=new Date().getHours(),period=hour>=5&&hour<12?'Morning':hour>=12&&hour<18?'Afternoon':'Evening';
 return [['Hello',owner],[`Good ${period},`,owner],['Welcome back,',owner],['What would you','like to do?'],['Ready when','you are.'],['What’s on','your mind?']];
}
// Pure function of elapsed time, so a Home re-render (greeting edit) resumes mid-phrase.
function greetingAt(elapsed,landscape){
 const typeMs=landscape?GREETING.type.landscape:GREETING.type.portrait;
 const steps=greetingPhrases().map(lines=>{
  const text=lines.join('\n'),n=text.length,typed=n*typeMs,blinked=typed+GREETING.blink*(GREETING.blinks*2-1),faded=blinked+GREETING.fade,held=faded+GREETING.hold,woke=held+GREETING.wake,erased=woke+n*GREETING.erase;
  return {text,n,typed,blinked,faded,held,woke,erased,end:erased+GREETING.gap};
 });
 let t=elapsed-GREETING.delay;
 if(t<0)return {phrase:steps[0].text,text:'',cursor:1};
 t%=steps.reduce((sum,s)=>sum+s.end,0);
 for(const s of steps){
  if(t>=s.end){t-=s.end;continue}
  const count=t<s.typed?Math.floor(t/typeMs):t<s.woke?s.n:t<s.erased?s.n-Math.floor((t-s.woke)/GREETING.erase):0;
  const cursor=t<s.typed?1:t<s.blinked?(Math.floor((t-s.typed)/GREETING.blink)%2===0?1:0):t<s.faded?1-(t-s.blinked)/GREETING.fade:t<s.held?0:t<s.woke?(t-s.held)/GREETING.wake:1;
  return {phrase:s.text,text:s.text.slice(0,count),cursor};
 }
}
// Shrink the type only when a phrase's longest line would not fit its column.
function fitGreeting(el,phrase){
 const key=[phrase,device.classList.contains('landscape'),device.style.fontFamily].join('|');
 if(el.dataset.fit===key)return;
 el.dataset.fit=key;el.style.fontSize='';
 const style=getComputedStyle(el),base=parseFloat(style.fontSize);
 const probe=document.createElement('span');probe.textContent='0';probe.style.cssText='position:absolute;visibility:hidden;font-size:1000px;white-space:pre';
 el.append(probe);const advance=probe.offsetWidth/1000;probe.remove();
 const avail=el.clientWidth-parseFloat(style.paddingLeft),longest=Math.max(...phrase.split('\n').map(line=>line.length));
 el.style.fontSize=Math.min(base,Math.floor(avail/(longest*advance+.64)*10)/10)+'px';
}
function paintGreeting(){
 const el=view.querySelector('.home-greeting');
 if(!el)return false;
 const frame=greetingAt(performance.now()-greetingStart,device.classList.contains('landscape'));
 fitGreeting(el,frame.phrase);
 const typed=el.querySelector('.typed');if(typed.textContent!==frame.text)typed.textContent=frame.text;
 const label=frame.phrase.replace('\n',' ');if(el.getAttribute('aria-label')!==label)el.setAttribute('aria-label',label);
 // Only touch the style when the cursor actually changes; it holds still for most frames.
 const opacity=frame.cursor.toFixed(2);if(el.dataset.cursor!==opacity){el.dataset.cursor=opacity;el.querySelector('.cursor').style.opacity=opacity}
 return true;
}
function tickGreeting(){greetingLoop=page==='Home'&&paintGreeting()?requestAnimationFrame(tickGreeting):0}
function mountGreeting(){
 if(matchMedia('(prefers-reduced-motion: reduce)').matches){
  const el=view.querySelector('.home-greeting'),phrase=`Hello\n${owner}`;
  fitGreeting(el,phrase);el.querySelector('.typed').textContent=phrase;el.setAttribute('aria-label','Hello '+owner);el.querySelector('.cursor').style.opacity=0;
  return;
 }
 paintGreeting();
 if(!greetingLoop)greetingLoop=requestAnimationFrame(tickGreeting);
}

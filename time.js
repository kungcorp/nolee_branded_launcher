function watchMarkup(){
 const ticks=Array.from({length:60},(_,i)=>`<line x1="120" y1="${i%5===0?10:14}" x2="120" y2="${i%5===0?21:18}" transform="rotate(${i*6} 120 120)" class="${i%5===0?'major':'minor'}"/>`).join('');
 return `<section class="watch-face" aria-label="Peace watch face"><div class="watch-heading"><span class="tag">TIME / PERSONAL</span><span class="watch-live">● LIVE</span></div><svg class="watch-ring" viewBox="0 0 240 240" aria-hidden="true">${ticks}<circle class="ring-track" cx="120" cy="120" r="96"/><circle id="seconds-ring" class="ring-progress" cx="120" cy="120" r="96" pathLength="60" transform="rotate(-90 120 120)"/><path class="ring-cross" d="M116 120h8M120 116v8"/></svg><div class="watch-time"><span class="watch-kicker">YOUR TIME, AT PEACE.</span><time id="watch-digits" class="watch-digits" role="timer" aria-live="off">--:--</time><div class="watch-seconds"><span class="seconds-indicator"></span><span id="watch-seconds" class="watch-seconds-value">00</span><span>SECONDS / 60</span></div></div><div class="watch-date"><span id="watch-day" class="watch-day">—</span><span id="watch-date" class="watch-date-value">—</span></div><div class="watch-zone"><span id="watch-zone" class="watch-zone-value">LOCAL TIME</span><span>24H</span></div></section>`;
}
function clockParts(now=new Date()){
 const pad=n=>String(n).padStart(2,'0');
 return {time:`${pad(now.getHours())}:${pad(now.getMinutes())}`,seconds:pad(now.getSeconds()),day:new Intl.DateTimeFormat('en',{weekday:'long'}).format(now).toUpperCase(),date:new Intl.DateTimeFormat('en-GB',{day:'2-digit',month:'short',year:'numeric'}).format(now).toUpperCase(),zone:Intl.DateTimeFormat().resolvedOptions().timeZone.replace(/_/g,' '),iso:now.toISOString()};
}
function updateClock(){
 const now=new Date(),parts=clockParts(now);
 const set=(id,value)=>{const element=document.getElementById(id);if(element&&element.textContent!==value)element.textContent=value};
 set('header-clock',parts.time);
 if(!document.getElementById('watch-digits'))return;
 set('watch-digits',parts.time);set('watch-seconds',parts.seconds);set('watch-day',parts.day);set('watch-date',parts.date);set('watch-zone',parts.zone);
 document.getElementById('watch-digits').setAttribute('datetime',parts.iso);
 document.getElementById('watch-digits').setAttribute('aria-label',`${parts.time}, ${parts.seconds} seconds`);
 document.getElementById('seconds-ring').style.strokeDasharray=`${now.getSeconds()} 60`;
}

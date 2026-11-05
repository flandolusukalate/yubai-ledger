// 日光 / 夜灯 home interactions: the hero figure rolls to its new value, and the month film
// can be scrubbed (a glass lens follows the finger) or tapped to open that day.
const reduce=()=>matchMedia('(prefers-reduced-motion: reduce)').matches;
const fmt=n=>new Intl.NumberFormat('en-US',{minimumFractionDigits:2,maximumFractionDigits:2}).format(Math.abs(n)/100);

// Count the hero amount from the previous value to the new one, like an odometer settling.
export function rollMoney(el,from,to){
  if(!el||from===null||from===undefined||from===to||reduce())return;
  const whole=el.querySelector('.g-int'),dec=el.querySelector('.g-dec-n');if(!whole||!dec)return;
  const start=performance.now(),duration=Math.min(1100,520+Math.abs(to-from)/40);
  const ease=t=>1-Math.pow(1-t,4);
  el.classList.add('is-rolling');
  const step=now=>{
    if(!el.isConnected)return;
    const t=Math.min(1,(now-start)/duration),v=Math.round(from+(to-from)*ease(t)),[i,d]=fmt(v).split('.');
    whole.textContent=i;dec.textContent=d;
    if(t<1)requestAnimationFrame(step);else el.classList.remove('is-rolling');
  };
  const [i,d]=fmt(from).split('.');whole.textContent=i;dec.textContent=d;requestAnimationFrame(step);
}

export function mountFilm(strip,{onDay}){
  if(!strip||strip.dataset.mounted)return;strip.dataset.mounted='1';
  const bars=[...strip.querySelectorAll('i')],month=strip.dataset.month,ref=Number(strip.dataset.ref)||0,last=Number(strip.dataset.last)||0;
  if(!bars.length)return;
  const lens=document.createElement('div');lens.className='g-film-lens';lens.setAttribute('aria-hidden','true');lens.hidden=true;strip.after(lens);
  let index=Math.max(0,Math.min(bars.length-1,(last||1)-1)),dragging=false,moved=false,startX=0;
  const text=i=>{
    const bar=bars[i],v=Number(bar.dataset.v||0),day=`${month.slice(5)}.${String(i+1).padStart(2,'0')}`;
    if(bar.classList.contains('future'))return [day,'还没到这一天',''];
    return [day,v?`¥${fmt(v)}`:'没有日常花费',v>ref&&ref?`超出 ¥${fmt(v-ref)}`:bar.classList.contains('today')?'今天':''];
  };
  const show=i=>{
    index=i;bars.forEach((b,n)=>b.classList.toggle('is-lit',n===i));
    const [day,value,note]=text(i);lens.replaceChildren();
    for(const [cls,t] of [['d',day],['v',value],['n',note]])if(t){const s=document.createElement('span');s.className=cls;s.textContent=t;lens.append(s);}
    lens.classList.toggle('is-over',!!note&&note.startsWith('超出'));
    lens.hidden=false;
    const sr=strip.getBoundingClientRect(),br=bars[i].getBoundingClientRect(),host=strip.parentElement.getBoundingClientRect();
    const w=lens.offsetWidth,center=br.left+br.width/2-host.left,left=Math.max(0,Math.min(host.width-w,center-w/2));
    lens.style.transform=`translate(${left}px,0)`;lens.style.setProperty('--tip',`${center-left}px`);
    lens.style.top=`${sr.top-host.top-lens.offsetHeight-10}px`;
    strip.setAttribute('aria-valuenow',String(i+1));strip.setAttribute('aria-valuetext',`${day} ${value} ${note}`.trim());
  };
  const hide=()=>{lens.hidden=true;bars.forEach(b=>b.classList.remove('is-lit'));};
  const at=x=>{const r=strip.getBoundingClientRect();return Math.max(0,Math.min(bars.length-1,Math.floor((x-r.left)/r.width*bars.length)));};
  strip.addEventListener('pointerdown',e=>{if(e.button)return;dragging=true;moved=false;startX=e.clientX;strip.setPointerCapture(e.pointerId);show(at(e.clientX));});
  strip.addEventListener('pointermove',e=>{if(!dragging)return;if(Math.abs(e.clientX-startX)>6)moved=true;const i=at(e.clientX);if(i!==index)show(i);});
  const end=e=>{if(!dragging)return;dragging=false;const i=at(e.clientX);if(!moved&&e.type==='pointerup'&&!bars[i].classList.contains('future'))onDay?.(i+1);setTimeout(hide,moved?900:250);};
  strip.addEventListener('pointerup',end);strip.addEventListener('pointercancel',end);
  // Horizontal scrubbing should not scroll the page.
  strip.style.touchAction='pan-y';
  strip.addEventListener('keydown',e=>{
    const map={ArrowLeft:-1,ArrowDown:-1,ArrowRight:1,ArrowUp:1};
    if(map[e.key]){e.preventDefault();show(Math.max(0,Math.min(bars.length-1,index+map[e.key])));}
    else if(e.key==='Home'||e.key==='End'){e.preventDefault();show(e.key==='Home'?0:bars.length-1);}
    else if((e.key==='Enter'||e.key===' ')&&!bars[index].classList.contains('future')){e.preventDefault();onDay?.(index+1);}
  });
  strip.addEventListener('focus',()=>{if(strip.matches(':focus-visible'))show(index);});strip.addEventListener('blur',hide);
}

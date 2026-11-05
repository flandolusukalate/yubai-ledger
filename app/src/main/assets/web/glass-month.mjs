// Glass month picker for the 日光 / 夜灯 appearances.
// Replaces the Android month dialog with a panel that unfolds out of the month pill.
// The hidden native <input type="month"> stays in place for the other themes.
const pad=n=>String(n).padStart(2,'0');

export function mountGlassMonth(host,{value,today,onPick}){
  if(!host||host.dataset.glassMonth)return;
  host.dataset.glassMonth='1';
  const input=host.querySelector('input');
  if(input){input.tabIndex=-1;input.style.pointerEvents='none';input.setAttribute('aria-hidden','true');}
  host.setAttribute('role','button');host.tabIndex=0;host.setAttribute('aria-haspopup','dialog');host.setAttribute('aria-expanded','false');
  host.setAttribute('aria-label',`账本月份：${Number(value.slice(0,4))} 年 ${Number(value.slice(5))} 月，点按选择`);
  let panel=null,year=Number(value.slice(0,4));

  const close=(focus=false)=>{
    if(!panel)return;
    const p=panel;panel=null;host.setAttribute('aria-expanded','false');host.classList.remove('is-open');
    document.removeEventListener('pointerdown',outside,true);document.removeEventListener('keydown',keys,true);
    if(matchMedia('(prefers-reduced-motion: reduce)').matches)p.remove();
    else{p.classList.add('is-closing');p.addEventListener('animationend',()=>p.remove(),{once:true});setTimeout(()=>p.remove(),400);}
    if(focus)host.focus({preventScroll:true});
  };
  const outside=e=>{if(panel&&!panel.contains(e.target)&&!host.contains(e.target))close();};
  const keys=e=>{if(e.key==='Escape'){e.preventDefault();e.stopPropagation();close(true);}};

  const paint=(dir=0)=>{
    panel.replaceChildren();
    const head=document.createElement('div');head.className='glass-month-head';
    const prev=document.createElement('button'),next=document.createElement('button'),title=document.createElement('strong');
    prev.type=next.type='button';prev.className=next.className='glass-month-step';prev.textContent='‹';next.textContent='›';
    prev.setAttribute('aria-label','上一年');next.setAttribute('aria-label','下一年');title.textContent=`${year} 年`;title.setAttribute('aria-live','polite');
    prev.onclick=()=>{year--;paint(-1);panel.querySelector('.glass-month-step').focus();};next.onclick=()=>{year++;paint(1);panel.querySelectorAll('.glass-month-step')[1].focus();};
    head.append(prev,title,next);
    const grid=document.createElement('div');grid.className='glass-month-grid';
    for(let m=1;m<=12;m++){
      const v=`${year}-${pad(m)}`,b=document.createElement('button');b.type='button';b.className='glass-month-cell';b.textContent=`${m} 月`;
      b.setAttribute('aria-pressed',String(v===value));if(v===today.slice(0,7))b.dataset.today='1';
      b.style.setProperty('--i',String(m-1));
      b.onclick=()=>{close();if(v!==value)onPick(v);else host.focus({preventScroll:true});};
      grid.append(b);
    }
    const back=document.createElement('button');back.type='button';back.className='glass-month-today';back.textContent='回到本月';
    back.onclick=()=>{close();const v=today.slice(0,7);if(v!==value)onPick(v);};
    panel.append(head,grid,back);
    if(dir&&!matchMedia('(prefers-reduced-motion: reduce)').matches){grid.dataset.slide='1';grid.animate([{opacity:0,transform:`translateX(${dir*28}px)`,filter:'blur(3px)'},{opacity:1,transform:'none',filter:'blur(0)'}],{duration:360,easing:'cubic-bezier(.16,1,.3,1)'});}
  };

  const open=()=>{
    if(panel)return close(true);
    year=Number(value.slice(0,4));
    panel=document.createElement('div');panel.className='glass-month';panel.setAttribute('role','dialog');panel.setAttribute('aria-label','选择账本月份');
    paint();host.after(panel);host.setAttribute('aria-expanded','true');host.classList.add('is-open');
    document.addEventListener('pointerdown',outside,true);document.addEventListener('keydown',keys,true);
    panel.querySelector('[aria-pressed="true"]')?.focus({preventScroll:true});
  };

  host.addEventListener('click',e=>{e.preventDefault();open();});
  host.addEventListener('keydown',e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();open();}});
}

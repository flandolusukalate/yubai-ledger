import {createGlassSystem} from './vendor/lens/runtime.js';

// UI-only motion. Monetary values are rendered directly by the ledger; never tween ledger data.
const O=globalThis.Overture;
const reduce=()=>matchMedia('(prefers-reduced-motion: reduce)').matches;
const views=new Set(),presses=new Map(),glass=[];
let route='',theme='paper',sheetSpring=null,focusOrigin=null,glassLayout='';
let sheetEpoch=0,sheetFrame=0,sheetClosing=false;
const navStates=new Map();
const stop=s=>{s?.stop();};
const finishViews=()=>{for(const s of views)s.finish();};
function spring(options,set){
  if(!O?.motion){set(options.value);return null;}
  const s=O.motion.spring({...options,onUpdate:set});views.add(s);return s;
}
function cancelAnimations(root){root?.getAnimations?.({subtree:true}).forEach(a=>a.cancel());}
function destroyGlass(keepNav=false){for(let i=glass.length-1;i>=0;i--){const g=glass[i];if(keepNav&&g.nav&&g.stage.isConnected)continue;g.destroy();glass.splice(i,1);}}
export function beforeRender(){
  destroyGlass(true);for(const s of views)stop(s);views.clear();
  for(const [el,s] of presses){s.stop();el.style.removeProperty('scale');}presses.clear();
  cancelAnimations(document.getElementById('main'));
}
function paintPaper(ctx,{width:w,height:h,dpr}){
  // This is the actual sampled scene, not a claim to refract arbitrary DOM behind the WebView.
  ctx.fillStyle='#eee8dc';ctx.fillRect(0,0,w,h);
  const wash=ctx.createRadialGradient(w*.85,h*.2,0,w*.85,h*.2,Math.max(w,h)*.7);
  wash.addColorStop(0,'#f9f4e9');wash.addColorStop(.6,'#e8ddcd');wash.addColorStop(1,'#eee8dc');ctx.fillStyle=wash;ctx.fillRect(0,0,w,h);
  ctx.strokeStyle='#a3948350';ctx.lineWidth=dpr*.5;
  for(let i=1;i<5;i++){ctx.beginPath();ctx.ellipse(w*.8,h*.5,w*.13*i,h*.23*i,-.25,0,Math.PI*2);ctx.stroke();}
  ctx.fillStyle='#a02b1d18';ctx.fillRect(w*.09,0,dpr*2,h);
}
function mountGlass(stage,surfaces){
  if(!stage||glass.some(g=>g.stage===stage)||!stage.getBoundingClientRect().height||!surfaces.length)return;
  for(const el of surfaces){el.classList.add('lg-surface');el.dataset.glass=el.matches('button')?'button':'panel';if(el.matches('button')&&!reduce())el.dataset.lgDrag='press';}
  let system;
  try{system=createGlassSystem(stage,{material:'clear',motion:false,elasticity:.70,artwork:paintPaper,audio:{enabled:false,storage:null},onFallback:()=>stage.dataset.optics='fallback'});}
  catch{stage.dataset.optics='fallback';return;}
  stage.dataset.optics=stage.classList.contains('webgl-ready')?'webgl':'fallback';
  const observer=new MutationObserver(()=>stage.dataset.optics=stage.classList.contains('webgl-ready')?'webgl':'fallback');observer.observe(stage,{attributes:true,attributeFilter:['class']});
  glass.push({stage,nav:['mobile-nav','desktop-links'].includes(stage.id),destroy(){observer.disconnect();system.destroy();for(const el of surfaces){el.classList.remove('lg-surface','jelly-moving');delete el.dataset.glass;delete el.dataset.lgDrag;delete el.dataset.jelly;delete el.dataset.held;el.style.removeProperty('--jelly-matrix');}stage.classList.remove('lg-stage','fallback');delete stage.dataset.optics;}});
}
function mountThemeGlass(){
  glassLayout=matchMedia('(max-width:760px)').matches?'mobile':'desktop';
  if(theme!=='lens')return;
  const nav=document.getElementById(glassLayout==='mobile'?'mobile-nav':'desktop-links');
  mountGlass(nav,[...nav.querySelectorAll('button')]);
  const hero=document.querySelector('.hero-main');if(hero)mountGlass(hero,[...hero.querySelectorAll('.hero-foot')]);
}
function moveIndicator(container,key){
  const active=container?.querySelector('[aria-current="page"]');if(!active||!active.getBoundingClientRect().width)return;
  let indicator=container.querySelector('.nav-marker');if(!indicator){indicator=document.createElement('i');indicator.className='nav-marker';indicator.setAttribute('aria-hidden','true');container.append(indicator);}
  const r=active.getBoundingClientRect(),p=container.getBoundingClientRect(),pill=theme==='glass'&&key==='mobile',target=pill?[r.left-p.left,r.top-p.top,r.width,r.height]:key==='mobile'?[r.left-p.left+12,0,r.width-24,2]:[0,r.top-p.top,2,r.height];
  let n=navStates.get(key);
  if(!n){n={element:indicator,spring:null};n.spring=O?.motion?.spring({value:target,preset:'snap',onUpdate:v=>{const e=n.element;if(!e?.isConnected)return;e.style.transform=`translate(${v[0]}px,${v[1]}px)`;e.style.width=v[2]+'px';e.style.height=v[3]+'px';}});navStates.set(key,n);}
  n.element=indicator;if(n.spring)n.spring.to(target,{instant:reduce()});else{indicator.style.left=target[0]+'px';indicator.style.top=target[1]+'px';indicator.style.width=target[2]+'px';indicator.style.height=target[3]+'px';}
}
export function afterRender({page,month,appearance}){
  if(theme!==appearance)destroyGlass();theme=appearance;document.documentElement.dataset.appearance=theme;
  const next=page+'|'+month+'|'+theme,changed=route!==next;route=next;
  moveIndicator(document.getElementById('desktop-links'),'desktop');moveIndicator(document.getElementById('mobile-nav'),'mobile');
  mountThemeGlass();
  if(changed&&!reduce()){
    const nodes=[...document.querySelectorAll('.page-heading,.hero,.panel,.summary-grid,.savings-card,.activity,.empty')].filter((e,i,a)=>!a.some(p=>p!==e&&p.contains(e))).slice(0,8);
    nodes.forEach((el,i)=>O?.motion?.develop(el,{duration:420,delay:i*35,from:'gray'}));
    document.querySelectorAll('.bar i').forEach(el=>{
      el.style.transformOrigin='left';el.style.scale='0 1';
      const s=spring({value:0,preset:'settle'},v=>{if(el.isConnected)el.style.scale=`${Math.max(0,v)} 1`;});s?.to(1);
    });
  }
  if(reduce())document.querySelectorAll('.bar i').forEach(el=>el.style.removeProperty('scale'));
}
function shade(){
  let el=document.getElementById('sheet-shade');
  if(!el){el=document.createElement('div');el.id='sheet-shade';el.setAttribute('aria-hidden','true');document.body.append(el);}
  return el;
}
// Overture's analytical spring easing is calculated once, then transform/opacity
// run in the compositor. No inherited CSS variable or JS layout work per frame.
function animateSheet(preset,tracks){
  const animations=tracks.map(([el,from,to])=>{
    Object.assign(el.style,to);
    return el.animate([from,to],{duration:O.motion.time(preset),easing:O.motion.easing(preset)});
  });
  return {stop(){animations.forEach(a=>a.cancel());},finish(){animations.forEach(a=>{try{a.finish();}catch{}});},
    done:Promise.all(animations.map(a=>a.finished)).then(()=>true,()=>false)};
}
export function openSheet(){
  const dialog=document.getElementById('sheet'),content=document.getElementById('sheet-content'),scrim=shade(),shell=document.querySelector('.shell');
  if(!dialog.open)focusOrigin=document.activeElement;
  const epoch=++sheetEpoch,origin=focusOrigin?.getBoundingClientRect?.();
  stop(sheetSpring);cancelAnimationFrame(sheetFrame);cancelAnimations(content);dialog.inert=false;sheetClosing=false;
  document.body.classList.add('sheet-open');scrim.hidden=false;dialog.style.removeProperty('transform');content.style.removeProperty('translate');
  if(reduce()){dialog.style.opacity='1';scrim.style.opacity='1';shell.style.removeProperty('transform');return;}
  dialog.style.opacity='0';scrim.style.opacity='0';
  sheetFrame=requestAnimationFrame(()=>{
    if(epoch!==sheetEpoch||!dialog.open)return;
    const r=dialog.getBoundingClientRect(),mobile=matchMedia('(max-width:760px)').matches;
    const x=origin?Math.max(0,Math.min(r.width,origin.left+origin.width/2-r.left)):r.width/2;
    dialog.style.transformOrigin=`${x}px ${mobile?r.height:0}px`;
    const distance=mobile?Math.min(160,r.height*.23):36;
    sheetSpring=animateSheet('settle',[
      [dialog,{transform:`translateY(${distance}px) scale(.94)`,opacity:.35},{transform:'none',opacity:1}],
      [scrim,{opacity:0},{opacity:1}],
      [shell,{transform:'scale(1)'},{transform:'scale(.988)'}]
    ]);
    const nodes=dialog.dataset.kind==='transaction'?[content.querySelector('.dialog-head'),content.querySelector('.transaction-amount')]:[content];
    nodes.filter(Boolean).forEach(el=>O.motion.develop(el,{duration:420,from:'gray'}));
  });
}
export function closeSheet(done){
  const dialog=document.getElementById('sheet');if(sheetClosing)return;sheetClosing=true;
  const epoch=++sheetEpoch,scrim=shade(),shell=document.querySelector('.shell');
  const d=getComputedStyle(dialog),start={transform:d.transform,opacity:d.opacity},background={transform:getComputedStyle(shell).transform},veil={opacity:getComputedStyle(scrim).opacity};
  cancelAnimationFrame(sheetFrame);stop(sheetSpring);cancelAnimations(document.getElementById('sheet-content'));
  if(reduce()){done();return;}
  dialog.inert=true;
  const matrix=new DOMMatrix(start.transform);
  sheetSpring=animateSheet('snap',[
    [dialog,start,{transform:`translateY(${matrix.m42+64}px) scale(${matrix.m11*.97})`,opacity:0}],
    [scrim,veil,{opacity:0}],
    [shell,background,{transform:'scale(1)'}]
  ]);
  sheetSpring.done.then(rest=>{if(rest&&epoch===sheetEpoch)done();});
}
export function sheetClosed(){
  if(document.getElementById('sheet').open)return;++sheetEpoch;cancelAnimationFrame(sheetFrame);stop(sheetSpring);sheetSpring=null;sheetClosing=false;
  const d=document.getElementById('sheet'),c=document.getElementById('sheet-content');d.inert=false;d.style.removeProperty('transform');d.style.removeProperty('opacity');cancelAnimations(c);c.style.removeProperty('translate');
  document.body.classList.remove('sheet-open');document.querySelector('.shell').style.removeProperty('transform');shade().hidden=true;
  if(focusOrigin?.isConnected)focusOrigin.focus({preventScroll:true});focusOrigin=null;
}
function press(el,target,keyboard=false){
  if(!el||el.disabled||el.closest('[aria-disabled="true"]')||(el.dataset.lgDrag&&!keyboard)||reduce())return;
  let s=presses.get(el);if(!s){s=O?.motion?.spring({value:1,preset:'snap',onUpdate:v=>{if(el.isConnected)el.style.scale=String(v);}});if(!s)return;presses.set(el,s);}
  s.to(target).then(done=>{if(done&&target===1&&s.target===1){s.stop();el.style.removeProperty('scale');presses.delete(el);}});
}
const control=e=>e.target.closest?.('button,.switch,.theme-choice,summary');
const release=()=>{for(const [el] of presses)press(el,1,true);};
document.addEventListener('pointerdown',e=>{if(e.isPrimary!==false)press(control(e),.975);},{passive:true});
document.addEventListener('pointerup',release,{passive:true});document.addEventListener('pointercancel',release,{passive:true});
document.addEventListener('keydown',e=>{if(!e.repeat&&['Enter',' '].includes(e.key))press(control(e),.975,true);});document.addEventListener('keyup',release);
document.addEventListener('pointermove',e=>{const el=control(e);if(el){const r=el.getBoundingClientRect();el.style.setProperty('--light-x',`${e.clientX-r.left}px`);el.style.setProperty('--light-y',`${e.clientY-r.top}px`);}},{passive:true});
document.addEventListener('invalid',e=>{e.target.setAttribute('aria-invalid','true');},{capture:true});
document.addEventListener('input',e=>{if(e.target.validity?.valid)e.target.removeAttribute('aria-invalid');});
document.addEventListener('change',e=>{if(e.target.matches('input[type="checkbox"],select'))O?.motion?.imprint(e.target);});
const settleAll=()=>{finishViews();for(const [el,s] of presses){s.set(1);el.style.removeProperty('scale');}presses.clear();sheetSpring?.finish();for(const n of navStates.values())n.spring?.finish();document.getAnimations().forEach(a=>{try{a.finish();}catch{a.cancel();}});};
matchMedia('(prefers-reduced-motion: reduce)').addEventListener('change',e=>{if(e.matches)settleAll();destroyGlass();mountThemeGlass();});
window.addEventListener('blur',release);
document.addEventListener('visibilitychange',()=>{if(document.hidden)settleAll();});
window.addEventListener('pagehide',()=>{beforeRender();destroyGlass();sheetClosed();for(const n of navStates.values())n.spring?.stop();navStates.clear();});
let resizeTimer;window.addEventListener('resize',()=>{clearTimeout(resizeTimer);resizeTimer=setTimeout(()=>{if(glassLayout!==(matchMedia('(max-width:760px)').matches?'mobile':'desktop')){destroyGlass();mountThemeGlass();}moveIndicator(document.getElementById('desktop-links'),'desktop');moveIndicator(document.getElementById('mobile-nav'),'mobile');},80);});

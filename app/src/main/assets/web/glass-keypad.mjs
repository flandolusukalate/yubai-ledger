// 日光 / 夜灯 record sheet: an in-sheet amount keypad (with + and −) and merchant memory.
// The amount input keeps its name, so the form contract and validation stay unchanged.
const OPS='+−';
export function evaluate(expr){
  const s=String(expr||'').replace(/\s/g,'').replace(/-/g,'−');
  if(!s)return null;
  if(!/^[0-9.]+([+−][0-9.]+)*[+−]?$/.test(s))return NaN;
  let total=0;
  for(const m of s.replace(/[+−]$/,'').matchAll(/([+−]?)([0-9.]+)/g)){
    if(!/^\d+(\.\d{0,2})?$|^\.\d{1,2}$/.test(m[2]))return NaN;
    const cents=Math.round(Number(m[2])*100);total+=m[1]==='−'?-cents:cents;
  }
  return total;
}
const cents2=n=>(n/100).toFixed(2);

export function mountKeypad(form,{merchants=[]}={}){
  const input=form.elements.namedItem('amount');if(!input)return {destroy(){}};
  const host=input.closest('.transaction-amount')||input.closest('.field');
  const coarse=matchMedia('(pointer: coarse)').matches;
  if(coarse)input.inputMode='none';
  const preview=document.createElement('div');preview.className='g-amount-preview';preview.setAttribute('aria-live','polite');
  const pad=document.createElement('div');pad.className='g-keypad';pad.setAttribute('role','group');pad.setAttribute('aria-label','金额键盘');
  const keys=[['1'],['2'],['3'],['⌫','删除一位','fn'],['4'],['5'],['6'],['+','加','op'],['7'],['8'],['9'],['−','减','op'],['.','小数点'],['0'],['00'],['完成','完成输入','done']];
  for(const [k,label,kind] of keys){const b=document.createElement('button');b.type='button';b.textContent=k;b.dataset.key=k;if(label)b.setAttribute('aria-label',label);if(kind)b.classList.add('is-'+kind);pad.append(b);}
  host.append(preview,pad);
  const sync=()=>{
    const v=input.value,total=evaluate(v),hasOp=/[+−-]/.test(v.slice(1));
    preview.textContent=hasOp?(Number.isNaN(total)||total===null?'':`= ¥${cents2(total)}`):'';
    input.dispatchEvent(new Event('input',{bubbles:true}));
  };
  const press=k=>{
    let v=input.value.replace(/-/g,'−');
    const lastNum=v.split(/[+−]/).pop();
    if(k==='⌫')v=v.slice(0,-1);
    else if(k==='完成'){finish();return;}
    else if(OPS.includes(k)){if(!v||OPS.includes(v.slice(-1)))v=v.replace(/[+−]$/,'');if(v)v+=k;}
    else if(k==='.'){if(!lastNum.includes('.'))v+=lastNum?'.':'0.';}
    else{ // digits: at most two decimals, no leading zeros
      let d=k;if(d==='00'&&(!lastNum||lastNum==='0'))d='0';
      if(lastNum.includes('.')){const room=2-lastNum.split('.')[1].length;d=d.slice(0,Math.max(0,room));if(!d)return;}
      else if(lastNum==='0')v=v.slice(0,-1);
      v+=d;
    }
    if(v.length>24)return;
    input.value=v;input.removeAttribute('aria-invalid');sync();
  };
  const finish=()=>{
    const total=evaluate(input.value);
    if(total!==null&&!Number.isNaN(total)&&/[+−]/.test(input.value.slice(1)))input.value=total>0?cents2(total):'';
    preview.textContent='';pad.hidden=true;host.classList.remove('is-keying');
    form.elements.namedItem('merchant')?.focus({preventScroll:false});
  };
  const open=()=>{pad.hidden=false;host.classList.add('is-keying');};
  pad.addEventListener('click',e=>{const b=e.target.closest('button');if(b)press(b.dataset.key);});
  // Long-press ⌫ clears the whole amount.
  let hold;pad.addEventListener('pointerdown',e=>{if(e.target.dataset?.key==='⌫')hold=setTimeout(()=>{input.value='';sync();},550);});
  pad.addEventListener('pointerup',()=>clearTimeout(hold));pad.addEventListener('pointerleave',()=>clearTimeout(hold));
  input.addEventListener('focus',open);
  input.addEventListener('keydown',e=>{if(e.key==='Enter'&&!pad.hidden&&/[+−-]/.test(input.value.slice(1))){e.preventDefault();finish();}});
  input.addEventListener('input',e=>{if(e.isTrusted){const total=evaluate(input.value),hasOp=/[+−-]/.test(input.value.slice(1));preview.textContent=hasOp&&total!==null&&!Number.isNaN(total)?`= ¥${cents2(total)}`:'';}});
  if(input.value)pad.hidden=true;else host.classList.add('is-keying');
  // Turn "18+4" into 22.00 before the sheet reads FormData.
  const onSubmit=e=>{
    if(!/[+−-]/.test(input.value.slice(1)))return;
    const total=evaluate(input.value);
    if(total===null||Number.isNaN(total)||total<=0){e.preventDefault();e.stopImmediatePropagation();input.setAttribute('aria-invalid','true');const err=form.querySelector('#form-error');if(err)err.textContent='算式结果须大于零，例如 18+4。';return;}
    input.value=cents2(total);
  };
  form.addEventListener('submit',onSubmit,{capture:true});

  // Merchant memory: tap a recent place to fill purpose, category and budget together.
  const merchant=form.elements.namedItem('merchant'),chips=document.createElement('div');chips.className='g-merchants';chips.setAttribute('role','group');chips.setAttribute('aria-label','最近的去处');
  const type=form.elements.namedItem('type');
  if(merchant&&merchants.length){
    merchant.closest('.field').after(chips);
    const paint=()=>{
      const q=merchant.value.trim(),list=merchants.filter(m=>m.name!==q&&(!q||m.name.includes(q))).slice(0,6);
      chips.replaceChildren(...list.map(m=>{const b=document.createElement('button');b.type='button';b.className='g-merchant';b.textContent=m.name;b.onclick=()=>{
        merchant.value=m.name;
        for(const [name,value] of [['category',m.category],['budgetId',m.budgetId]]){const sel=form.elements.namedItem(name);if(sel&&value&&[...sel.options].some(o=>o.value===value)&&sel.value!==value){sel.value=value;sel.dispatchEvent(new Event('change',{bubbles:true}));}}
        paint();
      };return b;}));
      chips.hidden=!list.length||(type&&type.value!=='expense');
    };
    merchant.addEventListener('input',paint);type?.addEventListener('change',paint);paint();
  }
  return {destroy(){form.removeEventListener('submit',onSubmit,{capture:true});}};
}

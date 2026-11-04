// Theme-owned transaction controls. Hidden source controls keep one FormData contract.
export function mountTransactionEditor(form,{icon,createCategory}){
  const controllers=[],observers=[];
  let activePicker=null;
  const node=(tag,cls,text)=>{const e=document.createElement(tag);if(cls)e.className=cls;if(text!==undefined)e.textContent=text;return e;};
  const button=(label,cls)=>{const b=node('button',cls,label);b.type='button';return b;};
  const input=name=>form.elements.namedItem(name);
  const field=name=>input(name).closest('.field');
  const grid=form.querySelector('.form-grid');grid.classList.add('transaction-fields');
  const hero=node('div','transaction-amount');const amount=field('amount');amount.querySelector('span').textContent='实际金额 · 人民币';
  const amountLine=node('div','amount-line');amountLine.append(node('span','currency','¥'),input('amount'));amount.append(amountLine);input('amount').placeholder='0.00';input('amount').autocomplete='off';input('amount').setAttribute('aria-label','实际金额');hero.append(amount);
  const typeField=field('type');grid.before(typeField,hero);
  field('merchant').classList.add('merchant-field');input('merchant').placeholder='记下这笔钱的去处';input('merchant').autocomplete='off';
  const advanced=node('details','transaction-advanced');advanced.append(node('summary','','时间与拆分明细'));const advancedBody=node('div','advanced-body');advanced.append(advancedBody);
  input('clock').type='text';input('clock').inputMode='text';input('clock').pattern='([01][0-9]|2[0-3]):[0-5][0-9]';input('clock').placeholder='12:30';
  advancedBody.append(field('clock'),field('splits'),grid.querySelector('.form-note'));grid.after(advanced);
  if(input('splits').value.trim())advanced.open=true;
  // Primary reading order is amount → purpose → category → funding → date.
  for(const name of ['merchant','category','accountId','budgetId','toAccountId','refundOf','matchPlan','date'])grid.append(field(name));
  const dismissPicker=()=>{if(!activePicker)return false;activePicker.close(true);return true;};
  function choice(source){
    let host=source.closest('.field');
    if(host.tagName==='LABEL'){const div=node('div',host.className);div.append(...host.childNodes);host.replaceWith(div);host=div;}
    const label=host.querySelector('span');label.id=`tx-label-${source.name}`;
    source.hidden=true;source.tabIndex=-1;source.setAttribute('aria-hidden','true');
    const always=['type','category'].includes(source.name);
    host.classList.add('choice-field',source.name==='type'?'type-field':source.name==='category'?'category-field':'compact-choice');
    const trigger=always?null:button('','choice-trigger');
    const options=node('div',always?(source.name==='type'?'type-options':'category-options'):'choice-options');
    options.setAttribute('role','group');options.setAttribute('aria-labelledby',label.id);options.id=`tx-options-${source.name}`;
    if(trigger){trigger.setAttribute('aria-expanded','false');trigger.setAttribute('aria-controls',options.id);trigger.setAttribute('aria-labelledby',`${label.id} tx-value-${source.name}`);host.append(trigger);options.hidden=true;}
    host.append(options);
    const controller={close(focus=false){if(!trigger)return;options.hidden=true;trigger.setAttribute('aria-expanded','false');if(activePicker===controller)activePicker=null;if(focus)trigger.focus({preventScroll:true});}};
    const render=()=>{
      options.replaceChildren();
      for(const option of source.options){
        const b=button('',source.name==='category'?'category-choice':'choice-option');b.dataset.choice=source.name;b.dataset.value=option.value;b.disabled=option.disabled;b.setAttribute('aria-pressed',String(option.value===source.value));
        if(source.name==='category')b.insertAdjacentHTML('beforeend',icon(option.value));
        const caption=source.name==='type'?({expense:'消费',income:'收入',refund:'退款',transfer:'转账',complex:'待核对'}[option.value]||option.textContent):option.textContent;
        b.setAttribute('aria-label',option.textContent);b.append(node('span','',caption));
        b.onclick=()=>{source.value=option.value;source.dispatchEvent(new Event('change',{bubbles:true}));controller.close(true);};options.append(b);
      }
      if(trigger){trigger.replaceChildren(node('span','choice-value',source.selectedOptions[0]?.textContent||'暂无可选项'),node('span','choice-chevron','⌄'));trigger.firstChild.id=`tx-value-${source.name}`;trigger.disabled=!source.options.length;}
    };
    if(trigger)trigger.onclick=()=>{const opening=options.hidden;dismissPicker();if(opening){options.hidden=false;trigger.setAttribute('aria-expanded','true');activePicker=controller;reveal(options);}};
    options.addEventListener('keydown',e=>{if(!['ArrowLeft','ArrowRight','ArrowUp','ArrowDown','Home','End'].includes(e.key))return;const buttons=[...options.querySelectorAll('button:not(:disabled)')],i=buttons.indexOf(document.activeElement);if(i<0)return;e.preventDefault();const next=e.key==='Home'?0:e.key==='End'?buttons.length-1:(i+(['ArrowLeft','ArrowUp'].includes(e.key)?-1:1)+buttons.length)%buttons.length;buttons[next].focus();});
    source.addEventListener('change',render);const observer=new MutationObserver(render);observer.observe(source,{childList:true});observers.push(observer);controllers.push(controller);render();
  }
  [...form.querySelectorAll('select')].forEach(choice);
  if(createCategory){
    const add=node('details','category-create'),summary=node('summary','','＋ 新建消费分类');add.append(summary);
    const name=node('input');name.placeholder='例如：学习资料、宠物、医疗';name.maxLength=24;name.setAttribute('aria-label','新分类名称');
    const save=button('添加并选中','btn'),note=node('p','muted','只增加消费标签，不自动分配预算。');add.append(name,save,note);field('category').append(add);
    save.onclick=()=>{try{const category=createCategory(name.value),source=input('category'),option=new Option(category.name,category.id,true,true);source.add(option);source.value=category.id;source.dispatchEvent(new Event('change',{bubbles:true}));form.querySelector('#form-error').textContent='';name.value='';add.open=false;}catch(e){form.querySelector('#form-error').textContent=e.message;}};
    name.onkeydown=e=>{if(e.key==='Enter'){e.preventDefault();save.click();}};
  }
  // Custom calendar inside the same sheet: never opens the Android date dialog.
  const dateInput=input('date'),dateHost=field('date');dateInput.type='hidden';dateHost.classList.add('date-field');
  const dateTrigger=button('','choice-trigger'),calendar=node('section','transaction-calendar');calendar.hidden=true;calendar.setAttribute('aria-label','选择交易日期');dateTrigger.setAttribute('aria-expanded','false');dateHost.append(dateTrigger,calendar);
  let viewed=dateInput.value.slice(0,7);
  const calendarController={close(focus=false){calendar.hidden=true;dateTrigger.setAttribute('aria-expanded','false');if(activePicker===calendarController)activePicker=null;if(focus)dateTrigger.focus({preventScroll:true});}};
  controllers.push(calendarController);
  function renderDate(){
    dateTrigger.textContent=dateInput.value.replace(/-/g,' / ')+'  ⌄';calendar.replaceChildren();
    const [year,month]=viewed.split('-').map(Number),head=node('div','calendar-head'),previous=button('‹','calendar-step'),next=button('›','calendar-step');previous.setAttribute('aria-label','上个月');next.setAttribute('aria-label','下个月');head.append(previous,node('strong','',`${year} 年 ${month} 月`),next);calendar.append(head);
    for(const [b,delta]of[[previous,-1],[next,1]])b.onclick=()=>{const d=new Date(Date.UTC(year,month-1+delta,1));viewed=d.toISOString().slice(0,7);renderDate();};
    const days=node('div','calendar-days');for(const name of ['一','二','三','四','五','六','日'])days.append(node('span','weekday',name));
    const offset=(new Date(Date.UTC(year,month-1,1)).getUTCDay()+6)%7,total=new Date(Date.UTC(year,month,0)).getUTCDate();
    for(let i=0;i<offset;i++)days.append(node('span'));
    for(let day=1;day<=total;day++){const value=`${viewed}-${String(day).padStart(2,'0')}`,b=button(String(day),'calendar-day');b.setAttribute('aria-label',value);b.setAttribute('aria-pressed',String(value===dateInput.value));b.onclick=()=>{dateInput.value=value;dateInput.dispatchEvent(new Event('change',{bubbles:true}));renderDate();calendarController.close(true);};days.append(b);}calendar.append(days);
  }
  dateTrigger.onclick=()=>{const opening=calendar.hidden;dismissPicker();if(opening){viewed=dateInput.value.slice(0,7);renderDate();calendar.hidden=false;dateTrigger.setAttribute('aria-expanded','true');activePicker=calendarController;reveal(calendar);}};renderDate();
  // Prevent native constraint bubbles; show the same paper error region and preserve draft.
  form.noValidate=true;form.addEventListener('submit',e=>{const invalid=[...form.elements].find(el=>el.willValidate&&!el.validity.valid);if(invalid){e.preventDefault();e.stopImmediatePropagation();const details=invalid.closest('details');if(details)details.open=true;invalid.setAttribute('aria-invalid','true');invalid.focus();form.querySelector('#form-error').textContent=invalid.name==='clock'?'请按 24 小时制填写时间，例如 12:30。':'请填写实际金额及必填内容。';}},{capture:true});
  const actions=form.querySelector('.form-actions'),error=form.querySelector('#form-error');actions.before(error);
  return {dismissPicker,destroy(){for(const observer of observers)observer.disconnect();}};
}
function reveal(el){if(!matchMedia('(prefers-reduced-motion: reduce)').matches)globalThis.Overture?.motion?.develop(el,{duration:240,from:'gray'});}

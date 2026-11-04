import {validateRules,approvedCustomPayment} from './rule-model.mjs';
import {similarPaymentPages,legacyReferenceContinuation,paymentIdentityGuard} from './payment-evidence.mjs';
import {CATEGORIES,categories,validateCategories,saveCategory,categorySpending} from './categories.mjs';
export {CATEGORIES,categories} from './categories.mjs';
/* All financial amounts are integer fen. UI, imports and collectors use this one reducer. */
export const VERSION = 1;
export const TYPES = {expense:'消费',income:'实际收入',refund:'退款',transfer:'账户间转账',complex:'其他用途（待核对）'};
export const uid = () => globalThis.crypto?.randomUUID?.() || `id-${Date.now()}-${Math.random().toString(36).slice(2)}`;
const clone = x => JSON.parse(JSON.stringify(x));
const fail = m => { throw new Error(m); };
export function money(v) {
  const s=String(v).trim(); if(!/^-?\d+(\.\d{1,2})?$/.test(s)) fail('金额须为最多两位小数');
  const negative=s.startsWith('-'), [a,b='']=s.replace('-','').split('.');
  const n=Number(a)*100+Number(b.padEnd(2,'0')); if(!Number.isSafeInteger(n)||n>1e12) fail('金额超出范围');
  return negative ? -n : n;
}
export const yuan = n => (n/100).toFixed(2);
export function dayAt(iso,zone='Asia/Shanghai') {
  if(!Number.isFinite(new Date(iso).getTime())) fail('日期无效');
  const p = new Intl.DateTimeFormat('en-CA',{timeZone:zone,year:'numeric',month:'2-digit',day:'2-digit'}).formatToParts(new Date(iso));
  return ['year','month','day'].map(k=>p.find(x=>x.type===k).value).join('-');
}
export function validDay(d) { return /^\d{4}-\d{2}-\d{2}$/.test(d)&&new Date(d+'T12:00:00Z').toISOString().slice(0,10)===d; }
export const monthOf = (s,t) => dayAt(t.occurredAt,s.settings.timezone).slice(0,7);
export const daysInMonth = m => new Date(Date.UTC(Number(m.slice(0,4)),Number(m.slice(5,7)),0)).getUTCDate();
export function localInstant(day,zone='Asia/Shanghai',clock='12:00') {
  if(!validDay(day)||!/^\d{2}:\d{2}$/.test(clock)) fail('日期或时间无效');
  let t=Date.parse(`${day}T${clock}:00Z`);
  for(let i=0;i<3;i++) {
    const parts=new Intl.DateTimeFormat('sv-SE',{timeZone:zone,year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'}).formatToParts(t);
    const p=k=>parts.find(x=>x.type===k).value;
    const displayed=Date.parse(`${p('year')}-${p('month')}-${p('day')}T${p('hour')}:${p('minute')}:${p('second')}Z`);
    t+=Date.parse(`${day}T${clock}:00Z`)-displayed;
  }
  if(dayAt(t,zone)!==day) fail('当地时间不存在'); return new Date(t).toISOString();
}
function cents(n,negative=false) { if(!Number.isSafeInteger(n)||Math.abs(n)>1e12||(!negative&&n<0)) fail('金额必须为有效整数分'); }
export function initialState() {
  return {version:VERSION,revision:0,settings:{timezone:'Asia/Shanghai',autoBook:false,autoRecordPayments:true,pageCapture:false,sources:[],theme:'paper'},
    accounts:[{id:'cash',name:'日常账户',type:'wallet',opening:0,openingDay:'1970-01-01'},{id:'savings',name:'储蓄账户',type:'savings',opening:0,openingDay:'1970-01-01'}],
    plans:{},categories:[],transactions:[],candidates:[],activities:[],adjustments:[],closures:{},audit:[]};
}
export function planProposal({month,income,savings,emergency=0,fixed=0,foodFloor=0,transportFloor=0,startDay=1}) {
  [income,savings,emergency,fixed,foodFloor,transportFloor].forEach(x=>cents(x));
  const base=savings+emergency+fixed+foodFloor+transportFloor, free=Math.max(0,income-base);
  const food=foodFloor+Math.floor(free*.5), transport=transportFloor+Math.floor(free*.1),fun=Math.floor(free*.18),goods=Math.floor(free*.10),trip=free-(food-foodFloor)-(transport-transportFloor)-fun-goods;
  return {month,income,savings,emergency,startDay,goalDeadline:`${month}-${String(daysInMonth(month)).padStart(2,'0')}`,gap:Math.max(0,base-income),budgets:[
    {id:'food',name:'餐饮',allocated:food,floor:foodFloor,daily:true}, {id:'transport',name:'交通',allocated:transport,floor:transportFloor,daily:true},
    {id:'fun',name:'娱乐',allocated:fun,floor:0,daily:true},{id:'goods',name:'日用品',allocated:goods,floor:0,daily:true},
    {id:'trip',name:'出游',allocated:trip,floor:0,daily:false},{id:'fixed',name:'固定费用',allocated:fixed,floor:0,daily:false}]};
}
export function allocationTotal(p) { return p.savings+p.emergency+p.budgets.reduce((n,b)=>n+b.allocated,0); }
function assertPlan(p) {
  if(!/^\d{4}-(0[1-9]|1[0-2])$/.test(p.month)) fail('月份无效');
  [p.income,p.savings,p.emergency].forEach(x=>cents(x));
  if(p.goalTarget!==undefined)cents(p.goalTarget);
  if(!Number.isInteger(p.startDay)||p.startDay<1||p.startDay>daysInMonth(p.month)) fail('启用日期无效');
  if(!Array.isArray(p.budgets)||new Set(p.budgets.map(x=>x.id)).size!==p.budgets.length) fail('预算项重复');
  for(const b of p.budgets) { cents(b.allocated);cents(b.floor||0);if(!b.id||!b.name||b.allocated<(b.floor||0)) fail('不能压低必要生活底线'); }
  if(p.goalDeadline&&!validDay(p.goalDeadline)) fail('目标截止日期无效');
}
const active = s => s.transactions.filter(t=>t.status==='confirmed');
export function accountBalance(s,id,through=null) {
  const a=s.accounts.find(x=>x.id===id); if(!a) fail('账户不存在');
  const openingDay=a.openingDay||'1970-01-01';if(through&&through<openingDay)return 0;
  return active(s).filter(t=>dayAt(t.occurredAt,s.settings.timezone)>=openingDay&&(!through||dayAt(t.occurredAt,s.settings.timezone)<=through)).reduce((n,t)=>{
    if(t.accountId===id) n += ['income','refund'].includes(t.type)?t.amount:-t.amount;
    if(t.type==='transfer'&&t.toAccountId===id) n+=t.amount; return n;
  },a.opening);
}
export function budget(s,month,id) {
  const p=s.plans[month],b=p?.budgets.find(x=>x.id===id); if(!b) return null;
  const shifts=s.adjustments.filter(a=>a.month===month).reduce((n,a)=>n+(a.to===id?a.amount:0)-(a.from===id?a.amount:0),0);
  let spent=0,refund=0;
  for(const t of active(s)) {
    if(monthOf(s,t)!==month)continue;
    if(t.type==='expense') spent+=t.splits.filter(x=>x.budgetId===id).reduce((n,x)=>n+x.amount,0);
    if(t.type==='refund' && monthOf(s,s.transactions.find(x=>x.id===t.refundOf))===month)
      refund+=t.splits.filter(x=>x.budgetId===id).reduce((n,x)=>n+x.amount,0);
  }
  return {...b,shifts,spent,refund,remaining:b.allocated+shifts-spent+refund};
}
export function reserve(s,month,key) { return (s.plans[month]?.[key]||0)+s.adjustments.filter(a=>a.month===month).reduce((n,a)=>n+(a.to===key?a.amount:0)-(a.from===key?a.amount:0),0); }
export function summary(s,month,today=dayAt(new Date(),s.settings.timezone)) {
  const p=s.plans[month], tx=active(s).filter(t=>monthOf(s,t)===month), sum=type=>tx.filter(t=>t.type===type).reduce((n,t)=>n+t.amount,0);
  const income=sum('income'),expenses=sum('expense'),refunds=sum('refund');
  const budgets=p?p.budgets.map(b=>budget(s,month,b.id)):[], daily=budgets.filter(b=>b.daily), D=daily.reduce((n,b)=>n+b.remaining,0);
  let T=0;
  for(const t of tx.filter(t=>dayAt(t.occurredAt,s.settings.timezone)===today)) {
    if(t.type==='expense') T+=t.splits.filter(x=>daily.some(b=>b.id===x.budgetId)).reduce((n,x)=>n+x.amount,0);
    if(t.type==='refund') {
      const original=s.transactions.find(x=>x.id===t.refundOf);
      if(dayAt(original.occurredAt,s.settings.timezone)===today) T-=t.splits.filter(x=>daily.some(b=>b.id===x.budgetId)).reduce((n,x)=>n+x.amount,0);
    }
  }
  const current=month===today.slice(0,7)&&!s.closures[month]&&p&&Number(today.slice(8))>=p.startDay;
  const N=current?daysInMonth(month)-Number(today.slice(8))+1:null;
  const total=N?Math.floor((D+T)/N):null, left=N?total-T:null;
  // Unclassified successful payments reserve daily capacity but are not formal
  // expenses. Prior-day holds stay in the month, so midnight cannot restore cash.
  const pendingPayments=s.candidates.filter(c=>c.status==='pending'&&c.autoRecorded===true&&!c.suspectedId&&!c.suspectedCandidateId&&Number.isSafeInteger(c.amount)&&c.amount>0);
  const held=pendingPayments.filter(c=>!c.holdReleased&&monthOf(s,c)===month);
  const pendingPaymentTotal=held.reduce((n,c)=>n+c.amount,0),dailyHold=held.filter(c=>dayAt(c.occurredAt,s.settings.timezone)===today).reduce((n,c)=>n+c.amount,0);
  const availableTotal=N?Math.floor((D-pendingPaymentTotal+T+dailyHold)/N):null,availableToday=N?availableTotal-T-dailyHold:null;
  const receivedPlan=tx.filter(t=>t.type==='income'&&t.incomePlan===month).reduce((n,t)=>n+t.amount,0);
  const previousRefund=tx.filter(t=>t.type==='refund'&&monthOf(s,s.transactions.find(x=>x.id===t.refundOf))!==month).reduce((n,t)=>n+t.amount,0);
  const walletBalance=s.accounts.reduce((n,a)=>n+accountBalance(s,a.id,`${month}-${String(daysInMonth(month)).padStart(2,'0')}`),0);
  // Paid cash stays reserved across months, even after releasing a daily hold.
  // A known account opening snapshot already includes payments before its date.
  const forecastPendingTotal=pendingPayments.filter(c=>{
    const day=dayAt(c.occurredAt,s.settings.timezone),account=s.accounts.find(a=>a.id===c.accountId);
    return day.slice(0,7)<=month&&(!account||day>=(account.openingDay||'1970-01-01'));
  }).reduce((n,c)=>n+c.amount,0),forecastBalance=walletBalance-forecastPendingTotal;
  const remainingCommitment=budgets.filter(b=>!b.daily).reduce((n,b)=>n+Math.max(0,b.remaining),0);
  // Replace the current month's corresponding unspent daily plan, not both.
  const plannedFuture=remainingCommitment+Math.max(0,D-pendingPaymentTotal);
  // Seven calendar days, counting zero-spend days, excluding fixed costs and trips.
  const nowDay=Number(today.slice(8)),elapsed=p?Math.max(1,nowDay-p.startDay+1):1,window=Math.min(7,elapsed),first=nowDay-window+1;
  const inRecentWindow=t=>Number(dayAt(t.occurredAt,s.settings.timezone).slice(8))>=first&&dayAt(t.occurredAt,s.settings.timezone)<=today;
  const recent=tx.filter(t=>t.type==='expense'&&t.splits.some(x=>daily.some(b=>b.id===x.budgetId))&&inRecentWindow(t));
  const recentPending=held.filter(inRecentWindow);
  const recentSpend=recent.reduce((n,t)=>n+t.splits.filter(x=>daily.some(b=>b.id===x.budgetId)).reduce((v,x)=>v+x.amount,0),0)+recentPending.reduce((n,c)=>n+c.amount,0);
  const basis=current&&elapsed>=7&&recent.length+recentPending.length>=3?'recent':'plan';
  const rhythmFuture=basis==='recent'?remainingCommitment+Math.round(recentSpend/window*Math.max(0,N-1))+Math.max(0,Math.round(recentSpend/window)-T-dailyHold):plannedFuture;
  return {income,expenses,refunds,net:income-expenses+refunds,categorySpending:categorySpending(s,tx),budgets,D,T,N,total,left,availableTotal,availableToday,pendingPaymentTotal,dailyHold,heldPayments:held.length,receivedPlan,previousRefund,walletBalance,
    savingsReserved:reserve(s,month,'savings'),emergencyReserved:reserve(s,month,'emergency'),
    savingsTransferred:tx.filter(t=>t.type==='transfer').reduce((n,t)=>n+(s.accounts.find(a=>a.id===t.toAccountId)?.type==='savings'?t.amount:0)-(s.accounts.find(a=>a.id===t.accountId)?.type==='savings'?t.amount:0),0),
    pending:s.candidates.filter(c=>c.status==='pending'),forecastPendingTotal,forecastBalance,plannedFuture,rhythmFuture,plannedForecast:forecastBalance-plannedFuture,rhythmForecast:forecastBalance-rhythmFuture,basis};
}
function assertTransaction(s,t,editing=false) {
  cents(t.amount);if(t.amount<=0)fail('金额必须大于零');
  if(!['expense','income','refund','transfer'].includes(t.type)) fail('复杂交易请先放入待确认');
  const month=monthOf(s,t); if(!s.accounts.some(a=>a.id===t.accountId))fail('请选择账户');
  if(t.type==='transfer'&&(!s.accounts.some(a=>a.id===t.toAccountId)||t.accountId===t.toAccountId))fail('转入与转出账户必须不同');
  if(t.type==='income'&&t.incomePlan) {
    if(!s.plans[t.incomePlan]||t.incomePlan!==month)fail('收入计划须属于到账月');
    const paid=active(s).filter(x=>x.type==='income'&&x.incomePlan===t.incomePlan&&x.id!==t.id).reduce((n,x)=>n+x.amount,0);
    if(t.status!=='void'&&paid+t.amount>s.plans[t.incomePlan].income)fail('超过未到账计划金额，请将超额部分另记收入');
  }
  if(t.type==='expense') {
    if(!Array.isArray(t.splits)||!t.splits.length||t.splits.reduce((n,x)=>n+x.amount,0)!==t.amount) fail('拆分明细之和必须等于支付总额');
    const labels=categories(s);
    for(const x of t.splits) {cents(x.amount);if(!x.amount||!Object.hasOwn(labels,x.category))fail('消费明细无效');if(!budget(s,month,x.budgetId))fail('该月尚无对应预算，请先创建月计划');}
  }
  if(t.type==='refund') {
    const o=s.transactions.find(x=>x.id===t.refundOf&&x.type==='expense'&&(x.status==='confirmed'||(editing&&t.status==='void')));if(!o)fail('请关联有效原消费');
    if(new Date(t.occurredAt)<new Date(o.occurredAt))fail('退款不能早于原消费');
    const prior=active(s).filter(x=>x.type==='refund'&&x.refundOf===o.id&&x.id!==t.id);
    if(t.status!=='void'&&prior.reduce((n,x)=>n+x.amount,0)+t.amount>o.amount)fail('累计退款不能超过原消费');
    if(!editing) {
      let remaining=t.amount;
      const grouped=[];for(const x of o.splits){const same=grouped.find(y=>y.budgetId===x.budgetId&&y.category===x.category);if(same)same.amount+=x.amount;else grouped.push({...x});}
      t.splits=grouped.map(x=>{
        const used=prior.reduce((n,r)=>n+r.splits.filter(y=>y.budgetId===x.budgetId&&y.category===x.category).reduce((a,y)=>a+y.amount,0),0);
        const available=o.splits.filter(y=>y.budgetId===x.budgetId&&y.category===x.category).reduce((a,y)=>a+y.amount,0)-used;
        const a=Math.min(remaining,available);remaining-=a;return {...x,amount:a};
      }).filter(x=>x.amount>0);
      if(remaining)fail('退款拆分关联异常');
    }
    if(!Array.isArray(t.splits)||t.splits.reduce((n,x)=>n+x.amount,0)!==t.amount)fail('退款拆分之和必须等于退款金额');
    if(t.status!=='void')for(const x of t.splits){
      cents(x.amount);const limit=o.splits.filter(y=>y.budgetId===x.budgetId&&y.category===x.category).reduce((n,y)=>n+y.amount,0);
      const total=[...prior,t].reduce((n,r)=>n+r.splits.filter(y=>y.budgetId===x.budgetId&&y.category===x.category).reduce((a,y)=>a+y.amount,0),0);
      if(!limit||total>limit)fail('退款预算累计超出原消费明细');
    }
  }
}
function markClosed(s,month) { if(s.closures[month])s.closures[month].needsReview=true; }
function post(s,raw) {
  const t={id:uid(),status:'confirmed',source:'manual',collectedAt:new Date().toISOString(),merchant:'',splits:[],...clone(raw)};
  t.merchant=String(t.merchant||'').slice(0,120);assertTransaction(s,t);
  const old=s.transactions.find(x=>x.id===t.id);
  if(old) {
    if(active(s).some(x=>x.refundOf===old.id))fail('已有退款关联，请先撤销退款再修改原消费');
    markClosed(s,monthOf(s,old));s.transactions=s.transactions.filter(x=>x.id!==t.id);
  }
  s.transactions.push(t);markClosed(s,monthOf(s,t));return t;
}
// Synchronous SHA-256 keeps the reducer deterministic in Node and offline WebView.
// Store a digest of input, never original OCR text. A 32-bit hash is insufficient for ledger deduplication.
function hash(s) {
  const k=[0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2];
  const h=[0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19],bytes=new TextEncoder().encode(s),padded=new Uint8Array(Math.ceil((bytes.length+9)/64)*64),w=new Uint32Array(64),rot=(x,n)=>(x>>>n)|(x<<(32-n));
  padded.set(bytes);padded[bytes.length]=128;const view=new DataView(padded.buffer),bits=bytes.length*8;view.setUint32(padded.length-8,Math.floor(bits/4294967296));view.setUint32(padded.length-4,bits>>>0);
  for(let offset=0;offset<padded.length;offset+=64){
    for(let i=0;i<16;i++)w[i]=view.getUint32(offset+i*4);
    for(let i=16;i<64;i++){const a=w[i-15],b=w[i-2];w[i]=((rot(a,7)^rot(a,18)^(a>>>3))+w[i-16]+(rot(b,17)^rot(b,19)^(b>>>10))+w[i-7])>>>0;}
    let [a,b,c,d,e,f,g,j]=h;
    for(let i=0;i<64;i++){const t1=(j+(rot(e,6)^rot(e,11)^rot(e,25))+((e&f)^(~e&g))+k[i]+w[i])>>>0,t2=((rot(a,2)^rot(a,13)^rot(a,22))+((a&b)^(a&c)^(b&c)))>>>0;j=g;g=f;f=e;e=(d+t1)>>>0;d=c;c=b;b=a;a=(t1+t2)>>>0;}
    [a,b,c,d,e,f,g,j].forEach((v,i)=>h[i]=(h[i]+v)>>>0);
  }return h.map(n=>n.toString(16).padStart(8,'0')).join('');
}
export function parsePayment(text,{source='ocr',origin='unknown',eventId='',collectedAt=new Date().toISOString(),occurredAt=null}={}) {
  const norm=String(text).replace(/\r/g,'').trim();
  // A sender's paid result and the recipient's acceptance are different states.
  // Use the complete result structure, never a status-bar number or largest amount.
  const lines=norm.normalize('NFKC').split('\n').map(x=>x.trim()).filter(Boolean);
  const resultHeader=lines.indexOf('支付成功'),resultDone=lines.indexOf('完成'),resultWait=lines.findIndex(x=>/^待.{1,160}确认收款$/.test(x));
  // The local Chinese OCR model may omit the currency glyph. Only a two-decimal
  // standalone value inside this complete result structure can replace it.
  const resultAmountLine=/^(?:¥\s*[0-9]+(?:\.[0-9]{1,2})?|[0-9]+\.[0-9]{2})$/;
  const amountLine=lines.findIndex(x=>resultAmountLine.test(x));
  const senderResult=['ocr','accessibility'].includes(source)&&resultHeader>=0&&resultHeader<12&&resultWait>resultHeader&&resultWait<=resultHeader+4&&amountLine>resultWait&&amountLine<=resultWait+3&&resultDone>amountLine&&lines.filter(x=>/^待.{1,160}确认收款$/.test(x)).length===1&&!/失败|取消|关闭|处理中|待支付|退款|退回|红包|余额|原价|优惠|手续费|总额|剩余|验证码|密码|口令|待(?:你|您|本人|自己|我)确认收款/.test(norm)&&!lines.some(x=>/^(发送|按住说话|聊天信息|通讯录|朋友圈)$/.test(x));
  const resultAmounts=senderResult?lines.filter(x=>resultAmountLine.test(x)).map(x=>{try{return money(x.replace(/^¥\s*/,''));}catch{return null;}}):[];
  const paidResult=senderResult&&resultAmounts.length===1&&resultAmounts[0]>0;
  const failed=/支付失败|付款失败|交易失败|转账失败|已取消|已关闭|退款失败/.test(norm),waiting=/待领取|待收款|等待[^\n]*(?:领取|收款|收钱)|处理中|待支付|退款中|退款申请/.test(norm);
  let type=/退款成功|退款到账|已退回/.test(norm)?'refund':/收款到账|收款成功|已收款|收入金额|已领取红包/.test(norm)?'income':/支付成功|付款成功|实付|支出金额/.test(norm)?'expense':null;
  const complex=senderResult||/转账|红包|充值|提现|还款|借款|代付|报销/.test(norm);
  const transfer=senderResult||(/转账/.test(norm)&&!/红包/.test(norm));
  const roles=type==='refund'?['退款金额','退款到账']:type==='income'?['本人领取','已领取红包','收款金额','收款到账','收入金额']:['实付金额','实际支付','实付','支付成功','付款成功','支出金额'];
  // Transfer amounts are useful evidence even when their purpose is not yet known.
  // Never promote them to ordinary expense/income merely because an amount is legible.
  if(transfer&&type!=='refund')roles.push('转账金额','转出金额','转账成功');
  const values=[];
  for(const label of roles) {
    const re=new RegExp(label+'[\\s:：人民币￥¥]*([0-9]+(?:\\.[0-9]{1,2})?)(?![0-9.,])','g');
    for(const m of norm.matchAll(re)) {try{values.push(money(m[1]));}catch{}}
  }
  const unique=[...new Set(values.filter(x=>x>0))],amount=senderResult?(paidResult?resultAmounts[0]:null):unique.length===1?unique[0]:null;
  const success=/支付成功|付款成功|收款到账|收款成功|已收款|退款成功|退款到账|已领取红包/.test(norm)||(transfer&&/转账成功|对方已收钱|对方已收款/.test(norm));
  const ref=norm.match(type==='refund'?/(?:退款单号|退款交易号)\s*[:：]?\s*([A-Za-z0-9_-]{6,80})/:/(?:交易单号|交易号|转账单号)\s*[:：]?\s*([A-Za-z0-9_-]{6,80})/)?.[1]||'';
  // A transfer recipient name may itself contain a merchant-like label.
  // The sender-result structure has no merchant role, just an acceptance line.
  const merchant=senderResult?'':norm.match(/(?:商户|商家|收款方)\s*[:：]\s*([^\n]{1,60})/)?.[1]?.trim()||'';
  const stamp=norm.match(/(20\d{2}[-/]\d{2}[-/]\d{2})[ T]+(\d{2}:\d{2}(?::\d{2})?)/);
  if(stamp&&!occurredAt)occurredAt=stamp[1].replaceAll('/','-')+'T'+stamp[2]+(stamp[2].length===5?':00':'')+'+08:00';
  if(occurredAt&&!Number.isFinite(Date.parse(occurredAt)))occurredAt=null;
  const category=/餐|食堂|咖啡|奶茶|外卖/.test(merchant)?'food':/地铁|公交|打车|交通/.test(merchant)?'transport':/电影|游戏|运动/.test(merchant)?'fun':/超市|日用品/.test(merchant)?'goods':null;
  return {id:uid(),fingerprint:hash(source+'|'+origin+'|'+eventId+'|'+norm),source,origin,eventId,reference:ref?origin+':'+ref:'',amount,type:complex?'complex':type,category,merchant,
    ...(paidResult?{direction:'out',fundsStatus:'paid_out',recipientStatus:'pending',timeBasis:occurredAt?'parsed-time':'observed-time'}:{}),
    occurredAt:occurredAt||collectedAt,collectedAt,status:failed?'rejected':'pending',successful:paidResult||(success&&!waiting&&!failed),
    reliable:source==='notification'&&origin!=='unknown'&&success&&!waiting&&!failed&&!complex&&amount!==null,
    reason:paidResult?'付款方已支付，对方尚待确认收款。若用于消费，请选择「消费」并核对预算；借款、代付等继续待核对。请核对实际交易日期，勿将截图时间当作交易时间。':failed?'支付失败或取消，不入账':waiting?'交易尚未完成，请核对本人实际资金状态':transfer&&success&&amount!==null?'已识别转账金额。用于消费请选择「消费」并核对预算；借款、代付等继续待核对':complex?'转账、红包或复杂用途须人工核对':amount===null?'金额缺失或金额角色冲突':!success?'缺少成功状态':!occurredAt&&source!=='notification'?'请核对交易时间':'请核对分类与承担预算'};
}
function flagSuspectedDuplicate(s,c) {
  const suspected=active(s).find(t=>{
    const source=s.candidates.find(x=>x.id===t.candidateId),reference=t.reference||source?.reference;
    if(differentOrigins(t.origin||source?.origin||reference?.split(':')[0],c.origin))return false;
    return (t.type===c.type||(c.type==='complex'&&c.direction==='out'&&['expense','transfer'].includes(t.type)))&&t.amount===c.amount&&!distinctReferences(reference,c.reference)&&Math.abs(Date.parse(t.occurredAt)-Date.parse(c.occurredAt))<=5*60000&&(!c.merchant||!t.merchant||t.merchant===c.merchant);
  });
  if(suspected){c.suspectedId=suspected.id;c.reason='金额与时间接近已有记录，请核对是否为同一笔';}
  return suspected;
}
function distinctReferences(a,b) {return !!(a&&b&&a!==b&&a.split(':')[0]===b.split(':')[0]);}
function differentOrigins(a,b){return ['wechat','alipay'].includes(a)&&['wechat','alipay'].includes(b)&&a!==b;}
function canRecordPayment(s,c) {
  if(s.settings.autoRecordPayments===false||c.status!=='pending'||c.successful!==true||!Number.isSafeInteger(c.amount)||c.amount<=0||c.amount>1e12)return false;
  const app={wechat:'com.tencent.mm',alipay:'com.eg.android.AlipayGphone'}[c.origin];
  if(!app||!s.settings.sources.includes(app))return false;
  if(c.source==='notification')return c.type==='expense'&&c.reliable===true;
  const readable=c.source==='accessibility'&&s.settings.pageCapture&&(!['accessibility-window-ocr','accessibility-window-ai'].includes(c.captureMethod)||s.settings.pageOcr===true);
  // screen_ocr remains a historical source for backup and duplicate links only.
  // Existing holds survive upgrades; no new automatic debit uses the retired collector.
  if(!readable||c.direction!=='out'||c.fundsStatus!=='paid_out')return false;
  if(c.captureMethod==='accessibility-window-ai')return c.type==='expense'&&c.evidence===`${c.origin}-ai-paid-page-v1`&&['page-observed-time','page-local-time-book-zone'].includes(c.timeBasis)&&(c.origin!=='alipay'||s.settings.alipayPageCapture===true);
  if(c.origin==='alipay')return s.settings.alipayPageCapture===true&&c.captureMethod==='accessibility-window-ocr'&&c.type==='expense'&&(c.evidence==='alipay-paid-page-v1'||approvedCustomPayment(s.settings,c));
  if(c.origin!=='wechat')return false;
  if(approvedCustomPayment(s.settings,c))return true;
  return c.type==='complex'&&(c.evidence==='wechat-paid-transfer-result-v1'||c.evidence==='wechat-paid-transfer-pending-detail-v1')||c.type==='expense'&&c.evidence==='wechat-paid-merchant-result-v1';
}
function flagPendingDuplicate(s,c) {
  if(!(c.autoRecorded||c.source==='ocr'&&c.status==='pending'&&c.type==='expense'&&c.successful===true)||c.suspectedId||c.suspectedCandidateId)return;
  // A no-ID image can be revisited long after its observation timestamp. Treat
  // matching receipt content as uncertain evidence, never a second automatic debit.
  const evidenceKey=c.paymentEvidenceKey||c.screenEvidenceKey;
  const validKey=k=>/^[a-f0-9]{64}$/.test(k||'');
  const conflictingIdentity=paymentIdentityGuard(s.candidates,c);
  if(['screen_ocr','accessibility','ocr'].includes(c.source)){
    // Same-reference updates have already replaced/linked their prior evidence.
    // Do not link the replacement back to its own merged predecessor.
    const image=s.candidates.find(x=>x.id!==c.id&&!differentOrigins(x.origin,c.origin)&&!(x.reference&&x.reference===c.reference)&&
      ((!distinctReferences(x.reference,c.reference)&&((validKey(evidenceKey)&&(x.paymentEvidenceKey||x.screenEvidenceKey)===evidenceKey)||similarPaymentPages(x,c)))||legacyReferenceContinuation(x,c,s.candidates))&&
      !conflictingIdentity(x)&&!['rejected','dismissed'].includes(x.status));
    if(image){const prior=resolvePaymentCandidate(s,image.id)||image;
      if(prior.transactionId)c.suspectedId=prior.transactionId;else c.suspectedCandidateId=prior.id;
      c.reason='页面内容与已有付款凭证相似，保留疑似关联，暂不重复预扣；若是另一笔真实付款，请核对后单独确认。';return;}
  }
  const prior=s.candidates.find(x=>x.status==='pending'&&(x.autoRecorded||x.source==='ocr'&&x.type==='expense'&&x.successful)&&x.id!==c.id&&x.amount===c.amount&&!differentOrigins(x.origin,c.origin)&&!distinctReferences(x.reference,c.reference)&&!conflictingIdentity(x)&&Math.abs(Date.parse(x.occurredAt)-Date.parse(c.occurredAt))<=5*60000&&(!c.merchant||!x.merchant||c.merchant===x.merchant));
  if(prior){c.suspectedCandidateId=prior.id;c.reason='可能与另一笔待补用途付款重复；本笔保留供核对，暂不重复预扣。若确为另一笔消费，补充用途后正常计入。';}
}
function resolvePaymentCandidate(s,id) {
  const seen=new Set();let c=s.candidates.find(x=>x.id===id);
  while(c?.duplicateOf){if(seen.has(c.id))fail('付款关联循环');seen.add(c.id);c=s.candidates.find(x=>x.id===c.duplicateOf);}
  return c;
}
function linkPaymentEvidence(s,c) {
  if(c.transactionId)for(const x of s.candidates)if(x.duplicateOf&&resolvePaymentCandidate(s,x.id)?.id===c.id)x.transactionId=c.transactionId;
}
function preserveMerchant(previous,next) {
  if(previous.merchantEdited===true){next.merchant=previous.merchant??'';next.merchantEdited=true;}
  else if(!next.merchant&&previous.merchant)next.merchant=previous.merchant;
}
function ingest(s,raw,{auto=true}={}) {
  const c={...clone(raw),id:raw.id||uid(),status:raw.status==='rejected'?'rejected':'pending'};
  if(c.source==='screen_ocr'){delete c.autoRecorded;c.reason='旧屏幕共享采集已停用；保留结果供手动核对，不新增自动预扣。';}
  if(auto&&canRecordPayment(s,c)){c.autoRecorded=true;c.reason='已自动记录成功付款，按日常消费预扣可用额度；补充用途后校正，不重复扣款。';}
  const previous=s.candidates.find(x=>x.fingerprint===c.fingerprint);
  if(previous){
    // A parser upgrade may recover an amount missing from the same evidence.
    // Enrich only unresolved candidates; never resurrect or rebook processed ones.
    if(previous.status==='pending'&&c.status==='pending'&&previous.amount==null&&Number.isSafeInteger(c.amount)&&c.amount>0){
      const {id,occurredAt,collectedAt}=previous;
      preserveMerchant(previous,c);
      Object.assign(previous,c,{id,occurredAt,collectedAt,status:'pending',reliable:false});
      flagSuspectedDuplicate(s,previous);
    }
    return;
  }
  const matches=c.reference?s.candidates.filter(x=>x.reference===c.reference&&(x.type===c.type||x.autoRecorded&&c.autoRecorded)&&x.status!=='rejected'&&x.status!=='dismissed'):[];
  const same=matches.find(x=>x.transactionId)||matches.find(x=>x.status==='pending'&&!x.duplicateOf)||matches[0];
  if(same&&!same.transactionId)preserveMerchant(same,c);
  // Keep when this exact transaction ID was first captured, so a later revisit
  // cannot erase the evidence that originally linked it to a no-ID success page.
  if(same?.autoRecorded&&c.autoRecorded&&same.amount===c.amount)c.firstCollectedAt=same.firstCollectedAt||same.collectedAt;
  const bookedAmount=same?.transactionId?s.transactions.find(t=>t.id===same.transactionId)?.amount:null;
  if(same?.transactionId&&bookedAmount===c.amount) {c.status='merged';c.transactionId=same.transactionId;c.reason='相同交易标识，已关联原记录';s.candidates.push(c);return;}
  if(same?.transactionId&&bookedAmount!==c.amount) {c.suspectedId=same.transactionId;c.reason='相同交易标识但金额有变化，请核对并修改原记录';c.reliable=false;}
  if(same&&!same.transactionId&&same.autoRecorded&&c.autoRecorded&&same.amount!=null&&same.amount!==c.amount){c.suspectedCandidateId=same.id;c.reason='相同交易标识但金额变化；两份证据保留待核对，本次不重复预扣。';}
  else if(same?.autoRecorded&&c.source==='ocr'){c.suspectedCandidateId=same.id;c.reason='已有该交易的自动付款记录，请关联或核对，不替换原预扣';}
  else if(same&&c.successful) {if(same.holdReleased)c.holdReleased=true;same.status='merged';same.duplicateOf=c.id;same.reason='已由该交易的更新状态替代';}
  flagSuspectedDuplicate(s,c);
  flagPendingDuplicate(s,c);
  s.candidates.push(c);
  // Payment evidence is saved first. A purpose decision is required before it
  // becomes formal consumption, even if an older backup enabled autoBook.
}
function receivePlan(s,month,receipt) {
  const p=s.plans[month];if(!p||s.closures[month])fail('请先创建或重新打开该月计划');
  if(!receipt||!s.accounts.some(a=>a.id===receipt.accountId))fail('请选择实际到账账户');
  if(monthOf(s,{occurredAt:receipt.occurredAt})!==month)fail('实际到账日期须属于该计划月份');
  const ids=receipt.existingIncomeIds||[];if(!Array.isArray(ids)||new Set(ids).size!==ids.length)fail('已有收入关联无效');
  for(const id of ids){
    const t=s.transactions.find(t=>t.id===id&&t.status==='confirmed'&&t.type==='income');
    if(!t||monthOf(s,t)!==month||t.incomePlan&&t.incomePlan!==month)fail('请选择本月未匹配的实际收入');
    if(t.incomePlan===month)continue;
    if(summary(s,month).receivedPlan+t.amount>p.income)fail('已有收入超过生活费计划，请先核对或拆分收入');
    t.incomePlan=month;
  }
  const remaining=p.income-summary(s,month).receivedPlan;
  if(remaining>0)post(s,{type:'income',amount:remaining,accountId:receipt.accountId,occurredAt:receipt.occurredAt,incomePlan:month,merchant:'生活费到账',source:'manual',planReceipt:true});
}
export function reduce(state,action) {
  const s=clone(state),v=action.value;
  switch(action.type) {
    case 'CATEGORY':saveCategory(s,v,v.id||'cat-'+uid());break;
    case 'PLAN': {
      assertPlan(v);const old=s.plans[v.month];
      const replay=old&&action.options?.receipt&&JSON.stringify(old)===JSON.stringify(v);
      if(old&&!replay&&(s.transactions.some(t=>monthOf(s,t)===v.month&&t.status==='confirmed')||s.adjustments.some(a=>a.month===v.month)||s.activities.some(a=>a.month===v.month)))fail('本月已有业务记录，请使用预算调拨；收入计划可单独修订');
      s.plans[v.month]=clone(v);if(action.options?.receipt)receivePlan(s,v.month,action.options.receipt);break;
    }
    case 'RECEIVE_PLAN':receivePlan(s,v.month,v);break;
    case 'PLAN_INCOME': {cents(v.amount);const p=s.plans[v.month];if(!p)fail('月计划不存在');if(v.amount<summary(s,v.month).receivedPlan)fail('不能低于已核销到账');p.income=v.amount;break;}
    case 'GOAL': {cents(v.amount);if(!validDay(v.deadline)||!s.plans[v.month])fail('目标信息无效');s.plans[v.month].goalTarget=v.amount;s.plans[v.month].goalDeadline=v.deadline;break;}
    case 'POST':post(s,v);break;
    case 'VOID': {
      const t=s.transactions.find(x=>x.id===v);if(!t)fail('记录不存在');
      if(active(s).some(x=>x.refundOf===v))fail('请先撤销关联退款');
      t.status=t.status==='void'?'confirmed':'void';
      if(t.status==='confirmed') assertTransaction(s,t,true);
      markClosed(s,monthOf(s,t));break;
    }
    case 'ADJUST': {
      const p=s.plans[v.month];if(!p||s.closures[v.month])fail('请先打开有效月计划');cents(v.amount);if(v.amount<=0||v.from===v.to)fail('调拨金额或方向无效');
      const keys=[...p.budgets.map(b=>b.id),'savings','emergency','unassigned'];
      if(!keys.includes(v.from)||!keys.includes(v.to))fail('预算项不存在');
      if(['savings','emergency'].some(k=>[v.from,v.to].includes(k))&&!v.confirmReserve)fail('调整储蓄或应急预留需要明确确认');
      const available=v.from==='unassigned'?unassigned(s,v.month):['savings','emergency'].includes(v.from)?reserve(s,v.month,v.from):budget(s,v.month,v.from).remaining;
      if(v.amount>available)fail('来源预算不足，不能转出不存在的预留');
      const b=budget(s,v.month,v.from);if(b&&b.allocated+b.shifts-v.amount<b.floor)fail('不能突破必要生活开支底线');
      s.adjustments.push({...v,id:uid(),at:new Date().toISOString()});break;
    }
    case 'INGEST': for(const c of (Array.isArray(v)?v:[v]))ingest(s,c,action.options);break;
    case 'CONFIRM': {
      const c=s.candidates.find(x=>x.id===v.id);if(!c||c.status!=='pending')fail('该记录已处理');
      if(c.suspectedCandidateId&&!v.distinctPayment)fail('请先判断疑似重复付款是同一笔还是另一笔');
      if(c.suspectedCandidateId&&c.reference&&resolvePaymentCandidate(s,c.suspectedCandidateId)?.reference===c.reference)fail('相同交易标识不能作为另一笔；请先校正原付款金额，再关联同一笔');
      if(c.reference){const prior=s.candidates.find(x=>x.id!==c.id&&x.reference===c.reference&&x.type===c.type&&x.transactionId);if(prior){if(v.transaction.amount!==s.transactions.find(t=>t.id===prior.transactionId)?.amount)fail('同一交易号已有账单且金额不一致，请先修改原账单，再关联此候选');c.status='merged';c.transactionId=prior.transactionId;break;}}
      const t=post(s,{...v.transaction,candidateId:c.id,source:c.source,collectedAt:c.collectedAt});c.status='booked';c.transactionId=t.id;linkPaymentEvidence(s,c);break;
    }
    case 'MERGE_PAYMENT': {
      const c=s.candidates.find(x=>x.id===v.id&&x.status==='pending'&&(x.autoRecorded||x.source==='ocr')),target=resolvePaymentCandidate(s,v.withId);
      if(!c||!target||target.id===c.id||!(target.autoRecorded||target.source==='ocr')||!['pending','booked'].includes(target.status))fail('不能关联这两笔付款');
      const targetAmount=target.transactionId?s.transactions.find(t=>t.id===target.transactionId)?.amount:target.amount;
      if(c.amount!==targetAmount)fail('付款金额不一致，请核对金额后再关联');
      c.status='merged';c.duplicateOf=target.id;c.reason='已确认与另一份付款记录为同一笔，不重复扣款';if(target.transactionId)c.transactionId=target.transactionId;break;
    }
    case 'MERGE': {
      const c=s.candidates.find(x=>x.id===v.id),t=active(s).find(x=>x.id===v.transactionId);
      if(!c||c.status!=='pending'||!t)fail('不能关联该记录');c.status='merged';c.transactionId=t.id;break;
    }
    case 'DISMISS': {const c=s.candidates.find(x=>x.id===v);if(c&&['pending','rejected'].includes(c.status))c.status='dismissed';break;}
    case 'RELEASE_PAYMENT_HOLD': {const c=s.candidates.find(x=>x.id===v&&x.status==='pending'&&x.autoRecorded);if(!c)fail('该付款没有待核对的预扣');c.holdReleased=true;c.reason='已标记为非日常消费，释放额度预扣；付款记录保留，等待补充用途。';break;}
    case 'UPDATE_PENDING': {const c=s.candidates.find(x=>x.id===v.id&&x.status==='pending');if(!c)fail('该候选已处理');cents(v.amount);if(v.amount<=0)fail('金额必须大于零');dayAt(v.occurredAt,s.settings.timezone);Object.assign(c,{amount:v.amount,merchant:String(v.merchant||'').slice(0,120),merchantEdited:true,occurredAt:v.occurredAt,type:'complex',holdReleased:true,reason:'复杂用途暂存，尚未计入正式收支；不预扣日常额度'});break;}
    case 'ACTIVITY': {
      const p=s.plans[v.month];if(!p||s.closures[v.month])fail('请先创建未关闭的月计划');
      cents(v.amount);if(!v.name||!validDay(v.date)||v.date.slice(0,7)<v.month)fail('活动名称或日期无效');
      const source=budget(s,v.month,v.from);if(!source||source.remaining<v.amount)fail('来源预算不足，请先调拨');
      if(source.allocated+source.shifts-v.amount<source.floor)fail('不能突破生活底线');
      const id=uid(),key='activity-'+id;p.budgets.push({id:key,name:v.name,allocated:0,floor:0,daily:false});
      s.adjustments.push({id:uid(),month:v.month,from:v.from,to:key,amount:v.amount});
      s.activities.push({...v,id,budgetId:key,status:'planned',components:v.components||{}});break;
    }
    case 'CANCEL_ACTIVITY': {
      const a=s.activities.find(x=>x.id===v);if(!a||a.status==='cancelled')fail('活动不存在或已取消');if(s.closures[a.month])fail('请先重新打开活动归属月');
      const b=budget(s,a.month,a.budgetId);if(b.remaining>0)s.adjustments.push({id:uid(),month:a.month,from:a.budgetId,to:'unassigned',amount:b.remaining});a.status='cancelled';break;
    }
    case 'ACCOUNT': {
      cents(v.opening,true);if(!v.name||!['wallet','savings','credit'].includes(v.type)||v.openingDay&&!validDay(v.openingDay))fail('账户信息无效');
      const id=v.id||uid(),old=s.accounts.find(a=>a.id===id);if(old&&!v.confirmOpening)fail('调整期初余额需要确认');
      if(old)Object.assign(old,v);else s.accounts.push({...v,id});break;
    }
    case 'SETTINGS': {
      if(v.customRules!==undefined)validateRules(v.customRules);
      if(v.timezone!==undefined){dayAt(new Date(),v.timezone);if(s.transactions.length&&v.timezone!==s.settings.timezone)fail('已有流水的账本暂不支持修改时区，以免改变归属月份');}
      if(v.pageCapture!==undefined&&typeof v.pageCapture!=='boolean')fail('页面识别开关无效');
      if(v.pageOcr!==undefined&&typeof v.pageOcr!=='boolean')fail('窗口 OCR 开关无效');
      if(v.alipayPageCapture!==undefined&&typeof v.alipayPageCapture!=='boolean')fail('支付宝页面识别开关无效');
      if(v.screenOcr!==undefined&&typeof v.screenOcr!=='boolean')fail('屏幕识别开关无效');
      if(v.autoRecordPayments!==undefined&&typeof v.autoRecordPayments!=='boolean')fail('自动记录开关无效');
      s.settings={...s.settings,...v};break;
    }
    case 'CLOSE': {
      if(!s.plans[v])fail('没有该月计划');if(s.candidates.some(c=>c.status==='pending'&&monthOf(s,c)===v))fail('请先处理本月待确认记录');
      if(v>=dayAt(new Date(),s.settings.timezone).slice(0,7))fail('只能结算已经结束的月份');
      s.closures[v]={at:new Date().toISOString(),net:summary(s,v).net,balance:summary(s,v).walletBalance,needsReview:false};break;
    }
    case 'REOPEN':delete s.closures[v];break;
    case 'CARRY_ACTIVITY': {
      const a=s.activities.find(x=>x.id===v.id),p=s.plans[v.toMonth];if(!a||!p||v.toMonth<=a.month)fail('请先创建活动目标月计划');
      if(s.closures[v.toMonth])fail('目标月已关闭');const remaining=Math.max(0,budget(s,a.month,a.budgetId).remaining);
      if(p.budgets.some(b=>b.id===a.budgetId))fail('已结转');
      p.budgets.push({id:a.budgetId,name:a.name,allocated:remaining,floor:0,daily:false,carried:true});
      s.adjustments.push({id:uid(),month:a.month,from:a.budgetId,to:'carried',amount:remaining});
      markClosed(s,a.month);a.previousMonths=[...(a.previousMonths||[]),a.month];a.month=v.toMonth;break;
    }
    default:fail('未知操作');
  }
  s.revision++;s.audit.push({id:uid(),action:action.type,at:new Date().toISOString()});return s;
}
export function unassigned(s,month) {
  const p=s.plans[month];if(!p)return 0;
  const total=p.budgets.filter(b=>!b.carried).reduce((n,b)=>n+b.allocated,0)+p.savings+p.emergency;
  return p.income-total+summary(s,month).previousRefund+s.adjustments.filter(a=>a.month===month).reduce((n,a)=>n+(a.to==='unassigned'?a.amount:0)-(a.from==='unassigned'?a.amount:0),0);
}
export function backup(s) {return JSON.stringify({format:'yubai-backup',version:VERSION,createdAt:new Date().toISOString(),state:s},null,2);}
export function restore(text) {
  if(text.length>20e6)fail('备份超过 20 MB');const b=JSON.parse(text);
  if(b.format!=='yubai-backup'||b.version!==VERSION||b.state?.version!==VERSION)fail('备份格式或版本不支持');
  const s=b.state;for(const k of ['accounts','transactions','candidates','activities','adjustments','audit'])if(!Array.isArray(s[k]))fail('备份缺少 '+k);
  if(!s.plans||!s.closures||!s.settings||!Number.isSafeInteger(s.revision))fail('备份结构不完整');
  dayAt(new Date(),s.settings.timezone);
  if(s.categories!==undefined)validateCategories(s.categories);
  if(!Array.isArray(s.settings.sources)||s.settings.sources.some(x=>!['com.tencent.mm','com.eg.android.AlipayGphone'].includes(x))||typeof s.settings.autoBook!=='boolean'||!['paper','stage','lens'].includes(s.settings.theme))fail('备份采集规则或主题无效');
  if(s.settings.customRules!==undefined)validateRules(s.settings.customRules);
  if(s.settings.pageCapture!==undefined&&typeof s.settings.pageCapture!=='boolean')fail('备份页面识别开关无效');
  if(s.settings.pageOcr!==undefined&&typeof s.settings.pageOcr!=='boolean')fail('备份窗口 OCR 开关无效');
  if(s.settings.alipayPageCapture!==undefined&&typeof s.settings.alipayPageCapture!=='boolean')fail('备份支付宝页面识别开关无效');
  if(s.settings.screenOcr!==undefined&&typeof s.settings.screenOcr!=='boolean')fail('备份屏幕识别开关无效');
  if(s.settings.autoRecordPayments!==undefined&&typeof s.settings.autoRecordPayments!=='boolean')fail('备份自动记录开关无效');
  for(const k of ['accounts','transactions','candidates','activities','adjustments'])if(new Set(s[k].map(x=>x.id)).size!==s[k].length||s[k].some(x=>!x.id))fail('备份存在重复标识');
  if(!s.accounts.some(a=>a.id==='cash')||!s.accounts.some(a=>a.id==='savings'))fail('备份缺少默认账户');
  for(const a of s.accounts){cents(a.opening,true);if(!['wallet','savings','credit'].includes(a.type)||typeof a.name!=='string'||a.openingDay&&!validDay(a.openingDay))fail('账户数据无效');}
  for(const [m,p] of Object.entries(s.plans)){assertPlan(p);if(m!==p.month)fail('计划月份不匹配');}
  for(const t of s.transactions) {
    if(!['confirmed','void'].includes(t.status))fail('流水状态无效');
    dayAt(t.collectedAt,s.settings.timezone);
    assertTransaction(s,clone(t),true);
    if(t.type==='refund') {
      const o=s.transactions.find(x=>x.id===t.refundOf);
      if(!Array.isArray(t.splits)||t.splits.reduce((n,x)=>n+x.amount,0)!==t.amount)fail('退款拆分不完整');
      for(const x of t.splits){cents(x.amount);if(!o.splits.some(y=>y.budgetId===x.budgetId&&y.category===x.category))fail('退款预算关联丢失');}
      if(t.status==='confirmed')for(const x of o.splits){const total=active(s).filter(r=>r.type==='refund'&&r.refundOf===o.id).reduce((n,r)=>n+r.splits.filter(y=>y.budgetId===x.budgetId&&y.category===x.category).reduce((a,y)=>a+y.amount,0),0),limit=o.splits.filter(y=>y.budgetId===x.budgetId&&y.category===x.category).reduce((n,y)=>n+y.amount,0);if(total>limit)fail('退款预算累计超出原明细');}
    }
  }
  for(const c of s.candidates){if(c.amount!==null&&c.amount!==undefined)cents(c.amount);dayAt(c.occurredAt,s.settings.timezone);if(c.transactionId&&!s.transactions.some(t=>t.id===c.transactionId))fail('来源关联丢失');if(!['pending','booked','merged','rejected','dismissed'].includes(c.status))fail('来源状态无效');for(const key of ['autoRecorded','holdReleased','merchantEdited'])if(c[key]!==undefined&&typeof c[key]!=='boolean')fail('付款记录状态无效');if(c.suspectedCandidateId&&(!s.candidates.some(x=>x.id===c.suspectedCandidateId)||c.suspectedCandidateId===c.id))fail('付款查重关联丢失');if(c.duplicateOf&&!resolvePaymentCandidate(s,c.id))fail('付款合并关联丢失');}
  for(const a of s.activities)if(!validDay(a.date)||!s.plans[a.month]?.budgets.some(b=>b.id===a.budgetId))fail('活动关联丢失');
  for(const a of s.adjustments) {cents(a.amount);const p=s.plans[a.month];if(!p)fail('调拨月份丢失');const ids=[...p.budgets.map(x=>x.id),'savings','emergency','unassigned','carried'];if(!ids.includes(a.from)||!ids.includes(a.to))fail('调拨关联丢失');}
  return clone(s);
}
export function parseCSV(text) {
  const rows=[];let row=[],field='',quoted=false;
  text=text.replace(/^\uFEFF/,'');
  for(let i=0;i<=text.length;i++){const c=text[i];if(c==='"'){if(quoted&&text[i+1]==='"'){field+='"';i++;}else if(!field||quoted)quoted=!quoted;else fail('CSV 引号格式错误');}
    else if((c===','||c==='\n'||c===undefined)&&!quoted){row.push(field.replace(/\r$/,''));field='';if(c!==','){if(row.some(x=>x.trim()))rows.push(row);row=[];}}
    else if(c!==undefined)field+=c;
  }
  if(quoted)fail('CSV 引号未闭合');return rows;
}
export const CSV_HEADER=['交易标识','交易时间','类型','金额','商户','分类','预算项','账户','状态','原交易标识'];
export function importCSV(text,s) {
  const rows=parseCSV(text);if(!rows.length||rows[0].join('|')!==CSV_HEADER.join('|'))fail('仅支持余白 v1 CSV；请下载模板，不支持直接导入其他平台文件');
  if(rows.length>10001)fail('单次最多导入 10000 行');
  return rows.slice(1).map((r,i)=>{
    try {
      if(r.length!==CSV_HEADER.length)fail('列数不符');if(!r[0])fail('交易标识不能为空');
      const type=Object.keys(TYPES).find(k=>k===r[2]||TYPES[k]===r[2]);if(!type||type==='complex')fail('暂不支持该类型');
      if(!/T.*(Z|[+-]\d{2}:\d{2})$/.test(r[1]))fail('交易时间需含时区，例如 2026-11-01T12:00:00+08:00');
      dayAt(r[1],s.settings.timezone);const amount=money(r[3]);if(amount<=0)fail('金额应大于零');
      const account=s.accounts.find(a=>a.id===r[7]||a.name===r[7]);if(!account)fail('账户不存在');
      const category=Object.entries(categories(s)).find(([id,name])=>id===r[5]||name===r[5])?.[0];
      if(type==='expense'&&r[5]&&!category)fail('消费分类不存在，请先添加分类或恢复完整备份');
      return {id:uid(),fingerprint:'csv:'+hash(r.join('|')),reference:'csv:'+r[0],source:'csv',origin:'csv',amount,type,merchant:r[4],category:category||r[5],budgetId:r[6],accountId:account.id,
        occurredAt:r[1],collectedAt:new Date().toISOString(),successful:r[8]==='成功',status:['失败','取消','撤销'].includes(r[8])?'rejected':'pending',reason:'文件导入，请核对后入账',refundRef:r[9],reliable:false};
    }catch(e){return {error:`第 ${i+2} 行：${e.message}`};}
  });
}
const cell=v=>'"'+String(v??'').replaceAll('"','""')+'"';
export function exportCSV(s) {
  return '\uFEFF'+[CSV_HEADER,...s.transactions.map(t=>[t.id,t.occurredAt,t.type,yuan(t.amount),/^[=+@\-]/.test(t.merchant)?"'"+t.merchant:t.merchant,
    t.splits?.length===1?t.splits[0].category:'',t.splits?.length===1?t.splits[0].budgetId:'',t.accountId,t.status==='confirmed'?'成功':'撤销',t.refundOf||''])].map(r=>r.map(cell).join(',')).join('\r\n');
}

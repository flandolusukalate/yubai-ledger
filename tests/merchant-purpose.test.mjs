import test from 'node:test';
import assert from 'node:assert/strict';
import * as C from '../app/src/main/assets/web/core.mjs';

const when='2026-11-02T04:00:00Z',month='2026-11';
function initial(){
 let s=C.reduce(C.initialState(),{type:'PLAN',value:{month,income:300000,savings:0,emergency:0,startDay:1,budgets:[{id:'food',name:'餐饮',allocated:300000,daily:true,floor:0}]}});
 return C.reduce(s,{type:'SETTINGS',value:{sources:['com.tencent.mm']}});
}
function payment(merchant='学校食堂'){
 return C.parsePayment(`支付成功20.00元\n商户：${merchant}\n交易号：merchant123`,{source:'notification',origin:'wechat',collectedAt:when});
}
function update(c,merchant){return {...c,id:C.uid(),fingerprint:C.uid(),merchant};}

test('same-reference evidence without merchant retains the already recognized merchant',()=>{
 const c=payment();let s=C.reduce(initial(),{type:'INGEST',value:c});
 const before=C.summary(s,month,'2026-11-02').availableToday;
 s=C.reduce(s,{type:'INGEST',value:update(c,'')});
 assert.equal(s.candidates.at(-1).merchant,'学校食堂');assert.equal(s.candidates.at(-1).status,'pending');
 assert.equal(s.transactions.length,0);assert.equal(C.summary(s,month,'2026-11-02').availableToday,before);
});
test('purpose edits including explicit clearing survive later same-reference merchant updates',()=>{
 for(const purpose of ['午餐代付','']){
  const c=payment();let s=C.reduce(initial(),{type:'INGEST',value:c});
  s=C.reduce(s,{type:'UPDATE_PENDING',value:{id:c.id,amount:2000,merchant:purpose,occurredAt:when}});
  assert.equal(s.candidates[0].merchantEdited,true);
  for(const incoming of ['新的商户','']){
   s=C.reduce(s,{type:'INGEST',value:update(c,incoming)});
   assert.equal(s.candidates.at(-1).merchant,purpose);assert.equal(s.candidates.at(-1).merchantEdited,true);assert.equal(s.candidates.at(-1).holdReleased,true);
  }
 }
});
test('recognized merchant can be amended at confirmation and later evidence cannot overwrite formal purpose',()=>{
 const c=payment();let s=C.reduce(initial(),{type:'INGEST',value:c});
 s=C.reduce(s,{type:'CONFIRM',value:{id:c.id,transaction:{type:'expense',amount:2000,merchant:'学校食堂 · 午餐',accountId:'cash',occurredAt:when,splits:[{category:'food',budgetId:'food',amount:2000}]}}});
 s=C.reduce(s,{type:'INGEST',value:update(c,'后续商户文字')});
 assert.equal(s.transactions.length,1);assert.equal(s.transactions[0].merchant,'学校食堂 · 午餐');assert.equal(s.candidates.at(-1).transactionId,s.transactions[0].id);
});
test('merchant edit provenance round-trips and malformed provenance is rejected',()=>{
 const c=payment();let s=C.reduce(initial(),{type:'INGEST',value:c});
 assert.deepEqual(C.restore(C.backup(s)),s,'old candidates without the optional marker still restore');
 s=C.reduce(s,{type:'UPDATE_PENDING',value:{id:c.id,amount:2000,merchant:'',occurredAt:when}});
 assert.deepEqual(C.restore(C.backup(s)),s);
 for(const value of ['true',1,null]){const bad=structuredClone(s);bad.candidates[0].merchantEdited=value;assert.throws(()=>C.restore(C.backup(bad)),/状态/);}
});
test('a merchant-like label inside a transfer recipient name is not saved as merchant',()=>{
 const c=C.parsePayment('支付成功\n待商户：某某确认收款\n5.20\n完成',{source:'ocr',collectedAt:when});
 assert.equal(c.amount,520);assert.equal(c.fundsStatus,'paid_out');assert.equal(c.merchant,'');assert.equal(c.category,null);
 assert.equal(payment().merchant,'学校食堂','explicit merchant roles outside transfer-result names remain supported');
});
test('recovering the amount of identical unresolved evidence preserves its merchant',()=>{
 const c=payment();let s=C.reduce(initial(),{type:'INGEST',value:{...c,amount:null}});
 s=C.reduce(s,{type:'INGEST',value:{...c,merchant:''}});
 assert.equal(s.candidates.length,1);assert.equal(s.candidates[0].amount,2000);assert.equal(s.candidates[0].merchant,'学校食堂');
});

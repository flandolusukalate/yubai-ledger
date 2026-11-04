import test from 'node:test';
import assert from 'node:assert/strict';
import * as C from '../app/src/main/assets/web/core.mjs';
const paidAt='2026-11-01T04:00:30Z',month='2026-11';
function base(){
 let s=C.reduce(C.initialState(),{type:'PLAN',value:{month,income:300000,savings:60000,emergency:15000,startDay:1,budgets:[['food',105000,true],['transport',15000,true],['fun',30000,true],['goods',15000,true],['trip',30000,false],['fixed',30000,false]].map(([id,allocated,daily])=>({id,name:id,allocated,daily,floor:0}))}});
 return C.reduce(s,{type:'SETTINGS',value:{sources:['com.tencent.mm'],pageCapture:true}});
}
const evidence=(overrides={})=>({id:C.uid(),fingerprint:C.uid(),source:'accessibility',origin:'wechat',evidence:'wechat-paid-transfer-result-v1',reference:'',amount:2000,type:'complex',direction:'out',fundsStatus:'paid_out',recipientStatus:'pending',successful:true,reliable:false,status:'pending',merchant:'',category:null,occurredAt:paidAt,collectedAt:paidAt,...overrides});
const sum=s=>C.summary(s,month,'2026-11-01');
test('successful live payment is recorded without advance choices and today 55 becomes 35 provisionally',()=>{
 const c=evidence();let s=C.reduce(base(),{type:'INGEST',value:c});
 assert.equal(s.transactions.length,0);assert.equal(s.candidates[0].autoRecorded,true);assert.equal(s.candidates[0].status,'pending');
 assert.equal(sum(s).total,5500);assert.equal(sum(s).left,5500);assert.equal(sum(s).dailyHold,2000);assert.equal(sum(s).availableTotal,5500);assert.equal(sum(s).availableToday,3500);
 s=C.reduce(s,{type:'CONFIRM',value:{id:c.id,transaction:{type:'expense',amount:2000,accountId:'cash',occurredAt:paidAt,splits:[{category:'food',budgetId:'food',amount:2000}]}}});
 assert.equal(sum(s).expenses,2000);assert.equal(sum(s).dailyHold,0);assert.equal(sum(s).availableToday,3500);assert.equal(C.budget(s,month,'food').remaining,103000);
 s=C.reduce(s,{type:'VOID',value:s.transactions[0].id});assert.equal(sum(s).availableToday,5500);
});
test('screenshots, unproven text, failed, incoming and uncertain payments do not provisionally deduct',()=>{
 for(const change of [{source:'ocr'},{evidence:undefined},{successful:false},{status:'rejected'},{direction:'in'},{fundsStatus:'unknown'},{amount:null},{type:'income'},{evidence:'wechat-transfer-detail'},{type:'refund'}]){
  const s=C.reduce(base(),{type:'INGEST',value:evidence(change)});assert.equal(s.transactions.length,0);assert.equal(sum(s).pendingPaymentTotal,0,JSON.stringify(change));
 }
 for(const settings of [{pageCapture:false},{sources:[]},{autoRecordPayments:false}])assert.equal(sum(C.reduce(C.reduce(base(),{type:'SETTINGS',value:settings}),{type:'INGEST',value:evidence()})).pendingPaymentTotal,0);
});
test('manual purpose may release the provisional deduction without deleting payment evidence',()=>{
 const c=evidence();let s=C.reduce(base(),{type:'INGEST',value:c});s=C.reduce(s,{type:'RELEASE_PAYMENT_HOLD',value:c.id});
 assert.equal(s.candidates[0].status,'pending');assert.equal(s.candidates[0].amount,2000);assert.equal(sum(s).availableToday,5500);assert.equal(s.transactions.length,0);
 const again=evidence();s=C.reduce(base(),{type:'INGEST',value:again});s=C.reduce(s,{type:'UPDATE_PENDING',value:{id:again.id,amount:2000,occurredAt:paidAt,merchant:'借款'}});assert.equal(sum(s).pendingPaymentTotal,0);
});
test('transaction internal transfer or nondaily expense releases daily hold without charging daily budget',()=>{
 for(const transaction of [{type:'transfer',toAccountId:'savings'},{type:'expense',splits:[{category:'food',budgetId:'trip',amount:2000}]}]){
  const c=evidence();let s=C.reduce(base(),{type:'INGEST',value:c});s=C.reduce(s,{type:'CONFIRM',value:{id:c.id,transaction:{amount:2000,accountId:'cash',occurredAt:paidAt,...transaction}}});
  assert.equal(sum(s).pendingPaymentTotal,0);assert.equal(sum(s).availableToday,5500);
 }
});
test('unclassified payment survives restart and reduces future daily reference until purpose is supplied',()=>{
 const s=C.restore(C.backup(C.reduce(base(),{type:'INGEST',value:evidence()})));
 const next=C.summary(s,month,'2026-11-02');assert.equal(next.pendingPaymentTotal,2000);assert.equal(next.dailyHold,0);assert.equal(next.availableToday,Math.floor(163000/29));
 assert.equal(C.summary(s,month,'2026-12-01').availableToday,null);
});
test('same evidence and same reference do not deduct twice; near matches are visible and deferred',()=>{
 const c=evidence({reference:'wechat:transfer123'});let s=C.reduce(base(),{type:'INGEST',value:[c,c,evidence({reference:c.reference,source:'notification',type:'expense',reliable:true})]});
 assert.equal(sum(s).pendingPaymentTotal,2000);assert.equal(s.transactions.length,0);
 const next=evidence();s=C.reduce(s,{type:'INGEST',value:next});assert.equal(sum(s).pendingPaymentTotal,2000);assert.ok(s.candidates.find(x=>x.id===next.id).suspectedCandidateId);
 const distinct=evidence({reference:'wechat:distinct456'});let two=C.reduce(base(),{type:'INGEST',value:[c,distinct]});assert.equal(sum(two).pendingPaymentTotal,4000,'known distinct references remain distinct');
 two=C.reduce(base(),{type:'INGEST',value:[evidence(),evidence({occurredAt:'2026-11-01T06:00:30Z',collectedAt:'2026-11-01T06:00:30Z'})]});assert.equal(sum(two).pendingPaymentTotal,4000);
});
test('resolving a possible duplicate requires a decision and same-payment merge never deducts twice',()=>{
 const a=evidence(),b=evidence();const pair=C.reduce(base(),{type:'INGEST',value:[a,b]});
 const transaction={type:'expense',amount:2000,accountId:'cash',occurredAt:paidAt,splits:[{category:'food',budgetId:'food',amount:2000}]};
 assert.throws(()=>C.reduce(pair,{type:'CONFIRM',value:{id:b.id,transaction}}),/疑似/);
 let same=C.reduce(pair,{type:'MERGE_PAYMENT',value:{id:b.id,withId:a.id}});same=C.reduce(same,{type:'CONFIRM',value:{id:a.id,transaction}});
 assert.equal(sum(same).availableToday,3500);assert.equal(same.candidates[1].transactionId,same.transactions[0].id);assert.equal(C.restore(C.backup(same)).candidates[1].duplicateOf,a.id);
 const distinct=C.reduce(pair,{type:'CONFIRM',value:{id:b.id,transaction,distinctPayment:true}});assert.equal(sum(distinct).availableToday,1500);
});
test('turning automatic recording off never falls through to legacy direct consumption booking',()=>{
 const s=C.reduce(C.reduce(base(),{type:'SETTINGS',value:{autoBook:true,autoRecordPayments:false}}),{type:'INGEST',value:evidence({source:'notification',type:'expense',reliable:true,category:'food'})});
 assert.equal(s.transactions.length,0);assert.equal(sum(s).pendingPaymentTotal,0);
});
test('matching formal expense is not charged provisionally again',()=>{
 let s=C.reduce(base(),{type:'POST',value:{type:'expense',amount:2000,accountId:'cash',occurredAt:paidAt,splits:[{category:'food',budgetId:'food',amount:2000}]}});
 s=C.reduce(s,{type:'INGEST',value:evidence()});assert.equal(sum(s).pendingPaymentTotal,0);assert.equal(sum(s).availableToday,3500);assert.ok(s.candidates[0].suspectedId);
});
test('a same-reference update preserves released purpose and keeps conflicting amounts for review',()=>{
 const c=evidence({reference:'wechat:transfer123'});let s=C.reduce(base(),{type:'INGEST',value:c});
 s=C.reduce(s,{type:'RELEASE_PAYMENT_HOLD',value:c.id});s=C.reduce(s,{type:'INGEST',value:evidence({reference:c.reference})});assert.equal(sum(s).pendingPaymentTotal,0);
 let conflict=C.reduce(base(),{type:'INGEST',value:[c,evidence({reference:c.reference,amount:3000})]});assert.equal(conflict.candidates[0].status,'pending');assert.equal(sum(conflict).pendingPaymentTotal,2000);assert.equal(conflict.candidates[1].suspectedCandidateId,c.id);
 const changed=conflict.candidates[1],transaction={type:'expense',amount:3000,accountId:'cash',occurredAt:paidAt,splits:[{category:'food',budgetId:'food',amount:3000}]};
 assert.throws(()=>C.reduce(conflict,{type:'CONFIRM',value:{id:changed.id,transaction,distinctPayment:true}}),/交易标识/);
 conflict=C.reduce(conflict,{type:'CONFIRM',value:{id:c.id,transaction}});conflict=C.reduce(conflict,{type:'MERGE_PAYMENT',value:{id:changed.id,withId:c.id}});assert.equal(conflict.transactions.length,1);assert.equal(sum(conflict).availableToday,2500);
});
test('ordinary reliable notifications and narrowly proven merchant pages auto-record without assigning a category',()=>{
 for(const change of [{source:'notification',type:'expense',reliable:true,evidence:undefined,direction:undefined,fundsStatus:undefined},{type:'expense',reliable:true,evidence:'wechat-paid-merchant-result-v1'}]){
  const s=C.reduce(base(),{type:'INGEST',value:evidence(change)});assert.equal(s.candidates[0].autoRecorded,true);assert.equal(s.transactions.length,0);assert.equal(sum(s).availableToday,3500);
 }
});
test('provisional overspend remains negative and optional fields in old backups still restore',()=>{
 const s=C.reduce(base(),{type:'INGEST',value:evidence({amount:200000})});assert.equal(sum(s).availableToday,-194500);
 const old=base();delete old.settings.autoRecordPayments;assert.deepEqual(C.restore(C.backup(old)),old);
 for(const change of [{autoRecorded:'yes'},{holdReleased:1},{suspectedCandidateId:'missing'}]){const bad=C.reduce(base(),{type:'INGEST',value:evidence()});Object.assign(bad.candidates[0],change);assert.throws(()=>C.restore(C.backup(bad)));}
});

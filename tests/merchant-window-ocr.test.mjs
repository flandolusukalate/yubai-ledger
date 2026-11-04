import test from 'node:test';
import assert from 'node:assert/strict';
import * as C from '../app/src/main/assets/web/core.mjs';
const when='2026-11-01T04:00:00Z',month='2026-11',day='2026-11-01';
function seed(){return C.reduce(C.reduce(C.initialState(),{type:'PLAN',value:{month,income:300000,savings:60000,emergency:15000,startDay:1,budgets:[{id:'food',name:'日常',allocated:165000,daily:true,floor:0}]}}),{type:'SETTINGS',value:{pageCapture:true,pageOcr:true,sources:['com.tencent.mm']}});}
function receipt(id='first',reference='wechat:12345678'){return {id,fingerprint:id,source:'accessibility',captureMethod:'accessibility-window-ocr',origin:'wechat',type:'expense',amount:2000,status:'pending',successful:true,reliable:false,direction:'out',fundsStatus:'paid_out',evidence:'wechat-paid-merchant-result-v1',paymentEvidenceKey:'c'.repeat(64),occurredAt:when,collectedAt:when,reference,merchant:'学校食堂'};}
const sum=s=>C.summary(s,month,day);
function confirm(s,id='first'){return C.reduce(s,{type:'CONFIRM',value:{id,transaction:{type:'expense',amount:2000,merchant:'学校食堂 · 午餐',accountId:'cash',occurredAt:when,splits:[{category:'food',budgetId:'food',amount:2000}]}}});}

test('merchant OCR records payment offline and immediately deducts 20 from 55, confirmation never deducts twice',()=>{
 let s=seed();assert.equal(sum(s).availableToday,5500);
 s=C.reduce(s,{type:'INGEST',value:receipt()});assert.equal(sum(s).availableToday,3500);assert.equal(sum(s).dailyHold,2000);assert.equal(s.candidates[0].merchant,'学校食堂');
 s=confirm(s);assert.equal(sum(s).availableToday,3500);assert.equal(sum(s).T,2000);assert.equal(sum(s).pendingPaymentTotal,0);assert.equal(s.transactions[0].merchant,'学校食堂 · 午餐');
});
test('repeat frames, reopens, process restart and backup restore preserve a single debit',()=>{
 let s=C.reduce(seed(),{type:'INGEST',value:receipt()});
 s=C.reduce(C.restore(C.backup(s)),{type:'INGEST',value:receipt()});assert.equal(s.candidates.length,1);
 for(let i=0;i<3;i++)s=C.reduce(s,{type:'INGEST',value:receipt('reopen-'+i)});
 assert.equal(sum(s).availableToday,3500);assert.equal(sum(s).pendingPaymentTotal,2000);
});
test('notification and OCR in either order combine the same transaction exactly once',()=>{
 const ocr=receipt(),notice={...receipt('notice'),source:'notification',captureMethod:undefined,reliable:true,paymentEvidenceKey:undefined};
 for(const pair of [[ocr,notice],[notice,ocr]]){
  let s=C.reduce(seed(),{type:'INGEST',value:pair});assert.equal(sum(s).availableToday,3500);
  const active=s.candidates.find(c=>c.status==='pending');s=confirm(s,active.id);
  s=C.reduce(s,{type:'INGEST',value:{...ocr,id:'later',fingerprint:'later'}});
  assert.equal(s.transactions.length,1);assert.equal(sum(s).availableToday,3500);
 }
});
test('two distinct WeChat transaction IDs remain two purchases even at same shop, amount and second',()=>{
 for(const booked of [false,true]){
  let s=C.reduce(seed(),{type:'INGEST',value:receipt()});if(booked)s=confirm(s);
  s=C.reduce(s,{type:'INGEST',value:receipt('second','wechat:87654321')});
  assert.equal(sum(s).availableToday,1500);assert.equal(s.candidates.at(-1).suspectedId,undefined);assert.equal(s.candidates.at(-1).suspectedCandidateId,undefined);
 }
});
test('without IDs, a revisited identical page stays suspected and does not deduct again',()=>{
 let s=C.reduce(seed(),{type:'INGEST',value:receipt('first','')});
 s=C.reduce(s,{type:'INGEST',value:{...receipt('reopened',''),occurredAt:'2026-11-01T10:00:00Z'}});
 assert.equal(sum(s).availableToday,3500);assert.equal(s.candidates.at(-1).suspectedCandidateId,'first');
 assert.throws(()=>confirm(s,'reopened'),/疑似重复/);
});
test('revoked OCR permission and unsuccessful payment cannot debit daily funds',()=>{
 for(const c of [{...receipt(),successful:false},{...receipt(),status:'rejected'}])assert.equal(sum(C.reduce(seed(),{type:'INGEST',value:c})).availableToday,5500);
 const s=C.reduce(seed(),{type:'SETTINGS',value:{pageOcr:false}});assert.equal(sum(C.reduce(s,{type:'INGEST',value:receipt()})).availableToday,5500);
});

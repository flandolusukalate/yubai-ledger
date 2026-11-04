import {test} from 'node:test';
import assert from 'node:assert/strict';
import * as C from '../app/src/main/assets/web/core.mjs';
const day='2026-11-02',month='2026-11';
function seed(){return C.reduce(C.reduce(C.initialState(),{type:'PLAN',value:{month,income:300000,savings:60000,emergency:15000,startDay:1,budgets:[{id:'food',name:'日常',allocated:165000,daily:true,floor:0}]}}),{type:'SETTINGS',value:{screenOcr:true,pageCapture:true,pageOcr:true,sources:['com.tencent.mm']}});}
function legacy(id='old-payment'){return {id,fingerprint:id,source:'screen_ocr',origin:'wechat',type:'complex',amount:1,status:'pending',successful:true,reliable:false,direction:'out',fundsStatus:'paid_out',recipientStatus:'pending',evidence:'wechat-paid-transfer-result-v1',screenEvidenceKey:'a'.repeat(64),occurredAt:'2026-11-02T10:00:00Z',collectedAt:'2026-11-02T10:00:00Z',reference:'',merchant:'',autoRecorded:true};}
function existing(){const s=seed();s.candidates.push(legacy());return C.restore(C.backup(s));}

test('old screen-sharing switch cannot auto-record new or replayed legacy queue payloads',()=>{
 for(const change of [{},{autoRecorded:undefined},{autoRecorded:true}]){
  const s=C.reduce(seed(),{type:'INGEST',value:{...legacy(),...change}});
  assert.equal(s.candidates.length,1);assert.equal(C.summary(s,month,day).dailyHold,0);assert.equal(s.transactions.length,0);
 }
});
test('removing screen capture preserves existing holds and their backup associations',()=>{
 const s=existing();assert.equal(C.summary(s,month,day).dailyHold,1);
 const restored=C.restore(C.backup(s));assert.deepEqual(restored,s);
 assert.equal(restored.settings.screenOcr,true,'legacy preference is inert but backup-compatible');
});
test('retrying previously recorded legacy fingerprint cannot erase its hold or debit twice',()=>{
 const s=C.reduce(existing(),{type:'INGEST',value:legacy()});
 assert.equal(s.candidates.length,1);assert.equal(s.candidates[0].autoRecorded,true);assert.equal(C.summary(s,month,day).dailyHold,1);
});
test('new accessibility OCR recognizes a historical screen-sharing receipt without second debit',()=>{
 let s=existing();const next={...legacy('new-window'),source:'accessibility',captureMethod:'accessibility-window-ocr',autoRecorded:undefined,paymentEvidenceKey:'a'.repeat(64),screenEvidenceKey:undefined,occurredAt:'2026-11-02T12:00:00Z'};
 s=C.reduce(s,{type:'INGEST',value:next});assert.equal(s.candidates[1].suspectedCandidateId,'old-payment');assert.equal(C.summary(s,month,day).dailyHold,1);
});
test('historical pending payment can still be confirmed and linked after retirement',()=>{
 let s=existing();const before=C.summary(s,month,day).availableToday;
 s=C.reduce(s,{type:'CONFIRM',value:{id:'old-payment',transaction:{type:'expense',amount:1,accountId:'cash',occurredAt:legacy().occurredAt,splits:[{category:'food',budgetId:'food',amount:1}]}}});
 assert.equal(s.transactions.length,1);assert.equal(C.summary(s,month,day).dailyHold,0);assert.equal(C.summary(s,month,day).availableToday,before);
 s=C.restore(C.backup(s));s=C.reduce(s,{type:'INGEST',value:{...legacy('new-window'),source:'accessibility',captureMethod:'accessibility-window-ocr',autoRecorded:undefined,paymentEvidenceKey:'a'.repeat(64),screenEvidenceKey:undefined,occurredAt:'2026-11-02T14:00:00Z'}});
 assert.equal(s.candidates[1].suspectedId,s.transactions[0].id);assert.equal(C.summary(s,month,day).availableToday,before);
});

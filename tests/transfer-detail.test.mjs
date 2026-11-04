import {test} from 'node:test';
import assert from 'node:assert/strict';
import * as C from '../app/src/main/assets/web/core.mjs';
const month='2026-11',day='2026-11-02';
function seed(){return C.reduce(C.reduce(C.initialState(),{type:'PLAN',value:{month,income:300000,savings:60000,emergency:15000,startDay:1,budgets:[{id:'food',name:'日常',allocated:165000,daily:true,floor:0}]}}),{type:'SETTINGS',value:{pageCapture:true,sources:['com.tencent.mm']}});}
function receipt(id='detail',time='2026-11-02T10:04:48Z'){return {id,fingerprint:id,source:'accessibility',origin:'wechat',type:'complex',amount:1,status:'pending',successful:true,reliable:false,direction:'out',fundsStatus:'paid_out',recipientStatus:'pending',evidence:'wechat-paid-transfer-pending-detail-v1',paymentEvidenceKey:'a'.repeat(64),occurredAt:time,collectedAt:'2026-11-02T11:00:00Z',reference:'',merchant:''};}
test('accessible sender detail reserves one cent; refresh, revisit and restart do not double deduct',()=>{
 let s=seed(),before=C.summary(s,month,day).availableToday;
 s=C.reduce(s,{type:'INGEST',value:receipt()});assert.equal(s.candidates[0].autoRecorded,true);assert.equal(C.summary(s,month,day).availableToday,before-1);assert.equal(s.transactions.length,0);
 s=C.reduce(s,{type:'INGEST',value:receipt()});assert.equal(s.candidates.length,1);
 s=C.restore(C.backup(s));s=C.reduce(s,{type:'INGEST',value:receipt('revisit')});assert.equal(s.candidates.length,2);assert.equal(C.summary(s,month,day).dailyHold,1);
 assert.equal(s.candidates[1].suspectedCandidateId,'detail');
});
test('success/detail across capture sources share suspicion even when observed times differ',()=>{
 let s=C.reduce(seed(),{type:'INGEST',value:receipt()});
 s=C.reduce(s,{type:'SETTINGS',value:{pageOcr:true}});
 s=C.reduce(s,{type:'INGEST',value:{...receipt('window-ocr','2026-11-02T12:00:00Z'),captureMethod:'accessibility-window-ocr',evidence:'wechat-paid-transfer-result-v1'}});
 assert.equal(s.candidates[1].suspectedCandidateId,'detail');assert.equal(C.summary(s,month,day).dailyHold,1);
 s=C.reduce(s,{type:'CONFIRM',value:{id:'detail',transaction:{type:'expense',amount:1,accountId:'cash',occurredAt:'2026-11-02T10:04:48Z',splits:[{category:'food',budgetId:'food',amount:1}]}}});
 s=C.reduce(s,{type:'INGEST',value:{...receipt('later','2026-11-02T13:00:00Z'),evidence:'wechat-paid-transfer-result-v1'}});
 assert.equal(s.candidates[2].suspectedId,s.transactions[0].id);assert.equal(C.summary(s,month,day).dailyHold,0);assert.equal(C.summary(s,month,day).expenses,1);
});
test('accessible detail still requires opt-in and clear outgoing funded evidence',()=>{
 for(const change of [{successful:false},{amount:null},{direction:'in'},{fundsStatus:'processing'},{evidence:'unknown'}]){
  let s=C.reduce(seed(),{type:'INGEST',value:{...receipt(),...change}});assert.equal(C.summary(s,month,day).dailyHold,0);
 }
 for(const change of [{pageCapture:false},{sources:[]},{autoRecordPayments:false}]){
  let s=C.reduce(seed(),{type:'SETTINGS',value:change});s=C.reduce(s,{type:'INGEST',value:receipt()});assert.equal(C.summary(s,month,day).dailyHold,0);
 }
});

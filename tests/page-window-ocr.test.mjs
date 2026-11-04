import {test} from 'node:test';
import assert from 'node:assert/strict';
import * as C from '../app/src/main/assets/web/core.mjs';
function seed(){return C.reduce(C.reduce(C.initialState(),{type:'PLAN',value:{month:'2026-11',income:300000,savings:60000,emergency:15000,startDay:1,budgets:[{id:'food',name:'日常',allocated:165000,daily:true,floor:0}]}}),{type:'SETTINGS',value:{pageCapture:true,pageOcr:true,sources:['com.tencent.mm']}});}
function receipt(id='ocr'){return {id,fingerprint:id,source:'accessibility',captureMethod:'accessibility-window-ocr',origin:'wechat',type:'complex',amount:1,status:'pending',successful:true,reliable:false,direction:'out',fundsStatus:'paid_out',recipientStatus:'pending',evidence:'wechat-paid-transfer-pending-detail-v1',paymentEvidenceKey:'b'.repeat(64),occurredAt:'2026-11-02T10:04:48Z',collectedAt:'2026-11-03T00:00:00Z',reference:'',merchant:''};}
test('historical OCR changes monthly funds once without inventing today consumption',()=>{
 let s=seed();const original=C.summary(s,'2026-11','2026-11-03');
 s=C.reduce(s,{type:'INGEST',value:receipt()});
 let sum=C.summary(s,'2026-11','2026-11-03');
 assert.equal(sum.pendingPaymentTotal,1);assert.equal(sum.dailyHold,0);assert.equal(sum.T,0);
 assert.equal(sum.availableToday,Math.floor((original.D-1)/28));
 assert.equal(C.summary(s,'2026-11','2026-11-02').dailyHold,1);
 s=C.restore(C.backup(s));s=C.reduce(s,{type:'INGEST',value:receipt()});
 assert.equal(s.candidates.length,1);
 s=C.reduce(s,{type:'INGEST',value:{...receipt('node'),captureMethod:'event-source'}});
 assert.equal(C.summary(s,'2026-11','2026-11-03').pendingPaymentTotal,1);
 assert.equal(s.candidates[1].suspectedCandidateId,'ocr');
 s=C.reduce(s,{type:'CONFIRM',value:{id:'ocr',transaction:{type:'expense',amount:1,accountId:'cash',occurredAt:receipt().occurredAt,splits:[{category:'food',budgetId:'food',amount:1}]}}});
 sum=C.summary(s,'2026-11','2026-11-03');assert.equal(sum.pendingPaymentTotal,0);assert.equal(sum.expenses,1);assert.equal(sum.T,0);assert.equal(sum.availableToday,Math.floor((original.D-1)/28));
});
test('queued window OCR honors revoked optional consent; node reading stays independent',()=>{
 let s=C.reduce(seed(),{type:'SETTINGS',value:{pageOcr:false}});
 s=C.reduce(s,{type:'INGEST',value:receipt()});assert.equal(C.summary(s,'2026-11','2026-11-03').pendingPaymentTotal,0);
 s=C.reduce(s,{type:'INGEST',value:{...receipt('node'),captureMethod:'event-source',paymentEvidenceKey:'c'.repeat(64)}});
 assert.equal(C.summary(s,'2026-11','2026-11-03').pendingPaymentTotal,1);
});
test('window OCR boolean survives full backup; invalid values are rejected',()=>{
 assert.equal(C.restore(C.backup(seed())).settings.pageOcr,true);
 assert.throws(()=>C.reduce(seed(),{type:'SETTINGS',value:{pageOcr:'true'}}));
 const invalid=seed();invalid.settings.pageOcr='true';assert.throws(()=>C.restore(C.backup(invalid)));
});

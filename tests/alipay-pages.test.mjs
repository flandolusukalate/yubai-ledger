import test from 'node:test';
import assert from 'node:assert/strict';
import * as C from '../app/src/main/assets/web/core.mjs';
const now='2026-11-03T08:00:00Z';
function base(){let s=C.initialState();s=C.reduce(s,{type:'PLAN',value:{month:'2026-11',income:300000,savings:60000,emergency:15000,startDay:3,budgets:[{id:'food',name:'餐饮',allocated:105000,daily:true,floor:0}]}});return C.reduce(s,{type:'SETTINGS',value:{pageCapture:true,pageOcr:true,alipayPageCapture:true,sources:['com.tencent.mm','com.eg.android.AlipayGphone']}});}
const c=(overrides={})=>({id:C.uid(),fingerprint:C.uid(),source:'accessibility',captureMethod:'accessibility-window-ocr',origin:'alipay',evidence:'alipay-paid-page-v1',type:'expense',amount:100,merchant:'测试商户',direction:'out',fundsStatus:'paid_out',successful:true,reliable:false,status:'pending',occurredAt:now,collectedAt:now,reference:'',timeBasis:'page-observed-time',paymentEvidenceKey:'1'.repeat(64),...overrides});
const sum=s=>C.summary(s,'2026-11','2026-11-03');
test('Alipay opt-in automatically holds payment once and preserves allowance accounting',()=>{const before=base(),s=C.reduce(before,{type:'INGEST',value:c()});assert.equal(sum(s).pendingPaymentTotal,100);assert.equal(sum(s).availableToday,sum(before).availableToday-100);assert.equal(s.transactions.length,0);});
test('Alipay requires its own opt-in, selected source and window OCR, never incoming/failed/complex',()=>{
 for(const settings of [{alipayPageCapture:false},{sources:['com.tencent.mm']},{pageCapture:false},{pageOcr:false},{autoRecordPayments:false}])assert.equal(sum(C.reduce(C.reduce(base(),{type:'SETTINGS',value:settings}),{type:'INGEST',value:c()})).pendingPaymentTotal,0);
 for(const delta of [{direction:'in'},{successful:false},{type:'refund'},{evidence:'alipay-unknown'},{captureMethod:'event-source'}])assert.equal(sum(C.reduce(base(),{type:'INGEST',value:c(delta)})).pendingPaymentTotal,0);
});
test('WeChat and Alipay same amount/time/merchant are separate payments',()=>{
 const wx=c({origin:'wechat',evidence:'wechat-paid-merchant-result-v1',paymentEvidenceKey:'2'.repeat(64)});
 const s=C.reduce(base(),{type:'INGEST',value:[wx,c()]});assert.equal(sum(s).pendingPaymentTotal,200);assert.ok(!s.candidates[1].suspectedCandidateId);
});
test('Alipay success/detail repeated evidence holds once, distinct complete IDs remain separate',()=>{
 const result=c(),bill=c({reference:'alipay:2026110300000000000000000001',referenceLayout:'bounded-lines-v1',timeBasis:'page-local-time-book-zone'});
 let s=C.reduce(base(),{type:'INGEST',value:[result,bill,{...bill,id:C.uid(),fingerprint:C.uid()}]});assert.equal(sum(s).pendingPaymentTotal,100);
 s=C.reduce(C.restore(C.backup(s)),{type:'INGEST',value:c({...bill,id:C.uid(),fingerprint:C.uid(),reference:'alipay:2026110300000000000000000002'})});assert.equal(sum(s).pendingPaymentTotal,200);
});

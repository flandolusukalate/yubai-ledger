import test from 'node:test';
import assert from 'node:assert/strict';
import * as C from '../app/src/main/assets/web/core.mjs';
import {draftRule,validateRules,screenshotPayment} from '../app/src/main/assets/web/rule-model.mjs';
import {base,change,payment,expense,summary,at} from './fixtures/forecast.mjs';
const imageKey='e'.repeat(64);
test('screenshot supplement keeps an existing automatic hold until the user links or confirms it',()=>{
 const automatic=payment(2000);let s=change(base(),'INGEST',automatic);
 const c=screenshotPayment(automatic,{imageKey,origin:'alipay',now:at(3)});
 assert.equal(c.source,'ocr');assert.equal(c.autoRecorded,undefined);
 s=change(s,'INGEST',c);assert.equal(summary(s).pendingPaymentTotal,2000);
 assert.equal(s.candidates.find(x=>x.id===automatic.id).status,'pending');
 assert.equal(s.candidates.find(x=>x.id===c.id).suspectedCandidateId,automatic.id);
 s=change(s,'MERGE_PAYMENT',{id:c.id,withId:automatic.id});
 s=change(s,'CONFIRM',{id:automatic.id,transaction:expense(2000,3)});
 assert.equal(summary(s).expenses,2000);assert.equal(summary(s).pendingPaymentTotal,0);
 s=change(s,'INGEST',screenshotPayment(automatic,{imageKey,origin:'alipay',now:at(3)}));
 assert.equal(summary(s).expenses,2000);assert.equal(s.candidates.length,2);
});
test('a new screenshot is only a candidate; replay after confirmation or ignoring never books again',()=>{
 for(const action of ['CONFIRM','DISMISS']){
  const c=screenshotPayment(payment(2000),{imageKey,origin:'alipay',now:at(3)});let s=change(base(),'INGEST',c);
  assert.equal(summary(s).pendingPaymentTotal,0);assert.equal(s.transactions.length,1);
  s=change(s,action,action==='CONFIRM'?{id:c.id,transaction:expense(2000,3)}:c.id);
  s=change(C.restore(C.backup(s)),'INGEST',screenshotPayment(payment(2000),{imageKey,origin:'alipay',now:at(3)}));
  assert.equal(s.candidates.length,1);assert.equal(s.transactions.length,action==='CONFIRM'?2:1);
 }
});
test('Alipay custom rules require the matching origin, explicit source toggle and tested window',()=>{
 const rule={...draftRule('ali-rule'),origin:'alipay',enabled:true,testedHash:'a'.repeat(64),windowClass:'com.alipay.mobile.nebulax.xriver.activity.XRiverActivity',matchText:'麦当劳',fields:{amount:{x:.2,y:.3,w:.4,h:.04},success:{x:.2,y:.1,w:.4,h:.04}}};
 validateRules([rule]);let s=change(base(),'SETTINGS',{customRules:[rule]});
 const c=payment(2000,3,'alipay',{evidence:'alipay-custom-paid-page-v1',customRuleId:rule.id,customRuleRevision:1,customRuleHash:rule.testedHash,customWindow:rule.windowClass});
 assert.equal(summary(change(s,'INGEST',c)).pendingPaymentTotal,2000);
 s=change(s,'SETTINGS',{alipayPageCapture:false});assert.equal(summary(change(s,'INGEST',c)).pendingPaymentTotal,0);
 assert.throws(()=>validateRules([{...rule,windowClass:'com.tencent.mm.AppBrandUI'}]));
});

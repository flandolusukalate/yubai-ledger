import test from 'node:test';
import assert from 'node:assert/strict';
import * as C from '../app/src/main/assets/web/core.mjs';
import {screenshotPayment} from '../app/src/main/assets/web/rule-model.mjs';
import {base,change,expense,payment,summary,at} from './fixtures/forecast.mjs';
const ai=(origin='wechat',patch={})=>({...payment(2000,1,origin),id:'ai',fingerprint:'ai-image',source:'ocr',captureMethod:'deepseek-fallback',evidence:undefined,fundsStatus:'unknown',reason:'DeepSeek 待核对',...patch});
test('AI pending receipt never debits even with automatic booking enabled; confirmation, edits and void use existing totals',()=>{
 for(const origin of ['wechat','alipay']){
  let s=change(base(),'SETTINGS',{autoBook:true});s=change(s,'INGEST',ai(origin));
  assert.equal(summary(s,1).availableToday,5500);assert.equal(summary(s,1).forecastPendingTotal,0);assert.equal(s.transactions.length,1);
  s=change(s,'CONFIRM',{id:'ai',transaction:expense(2000,1,'food','paid')});
  assert.equal(summary(s,1).availableToday,3500);assert.equal(summary(s,1).walletBalance,298000);
  s=change(s,'POST',expense(1000,1,'food','paid'));assert.equal(summary(s,1).availableToday,4500);
  s=change(s,'VOID','paid');assert.equal(summary(s,1).availableToday,5500);assert.equal(summary(s,1).walletBalance,300000);
 }
});
test('repeated image before/after restart and confirmation cannot charge twice',()=>{
 let s=change(base(),'INGEST',ai());s=change(C.restore(C.backup(s)),'INGEST',ai());assert.equal(s.candidates.length,1);
 s=change(s,'CONFIRM',{id:'ai',transaction:expense(2000,1)});s=change(s,'INGEST',ai());assert.equal(summary(s,1).availableToday,3500);assert.equal(s.transactions.length,2);
});
test('AI image and existing local payment with one platform ID stay linked with a single hold',()=>{
 for(const origin of ['wechat','alipay']){
  const ref=origin+':verified-platform-id',local=payment(2000,1,origin,{id:'local',reference:ref});
  let s=change(base(),'INGEST',local);s=change(s,'INGEST',ai(origin,{reference:ref}));
  assert.equal(summary(s,1).availableToday,3500);assert.equal(s.candidates.find(c=>c.id==='ai').suspectedCandidateId,'local');
  s=change(s,'CONFIRM',{id:'local',transaction:expense(2000,1)});s=change(s,'INGEST',ai(origin,{id:'again',fingerprint:'other-image',reference:ref}));
  assert.equal(summary(s,1).availableToday,3500);assert.equal(s.candidates.at(-1).status,'merged');
 }
});
test('unknown/failed/refund AI evidence does not alter balances or forecast and no key enters backup',()=>{
 for(const patch of [{amount:null,successful:false},{successful:false,type:'complex'},{type:'complex',direction:'in'}]){
  const s=change(base(),'INGEST',ai('wechat',patch));assert.equal(summary(s,1).availableToday,5500);assert.equal(summary(s,1).plannedForecast,75000);assert.equal(summary(s,1).walletBalance,300000);
  assert.equal(C.backup(s).includes('apiKey'),false);
 }
});
test('AI screenshot preview retains its source, preserves unknown amount and cannot become rule proof',()=>{
 const c=screenshotPayment(ai('alipay',{amount:null,successful:false}),{imageKey:'a'.repeat(64),origin:'alipay',now:at(1)});
 assert.equal(c.captureMethod,'deepseek-fallback');assert.equal(c.amount,null);assert.equal(c.source,'ocr');assert.equal(c.reliable,false);assert.equal(c.customRuleHash,undefined);
 assert.equal(summary(change(base(),'INGEST',c),1).availableToday,5500);
});

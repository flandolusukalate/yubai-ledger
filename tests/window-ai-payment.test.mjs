import test from 'node:test';
import assert from 'node:assert/strict';
import * as C from '../app/src/main/assets/web/core.mjs';
import {base,change,expense,payment,summary,at} from './fixtures/forecast.mjs';
const live=(origin='wechat',patch={})=>payment(2000,1,origin,{id:'ai',fingerprint:'image-1',captureMethod:'accessibility-window-ai',evidence:`${origin}-ai-paid-page-v1`,...patch});

test('authorized live DS payment deducts 20 from 55 without confirmation, purpose, edit and void update once',()=>{
 for(const origin of ['wechat','alipay']){
  let s=change(base(),'INGEST',live(origin));
  assert.equal(summary(s,1).availableToday,3500);assert.equal(summary(s,1).forecastPendingTotal,2000);
  s=change(s,'CONFIRM',{id:'ai',transaction:expense(1000,1,'food','paid')});
  assert.equal(summary(s,1).availableToday,4500);assert.equal(summary(s,1).forecastPendingTotal,0);
  s=change(s,'POST',expense(1500,1,'food','paid'));assert.equal(summary(s,1).availableToday,4000);
  s=change(s,'VOID','paid');assert.equal(summary(s,1).availableToday,5500);
 }
});

test('live AI, local OCR and notification with a platform ID survive replay, restart and confirmation with one debit',()=>{
 for(const origin of ['wechat','alipay'])for(const reverse of [false,true]){
  const cloud=live(origin,{reference:`${origin}:order-1`}),local=payment(2000,1,origin,{id:'local',reference:cloud.reference});
  let s=change(base(),'INGEST',reverse?[local,cloud]:[cloud,local]);
  s=change(C.restore(C.backup(s)),'INGEST',[cloud,local]);
  assert.equal(summary(s,1).availableToday,3500);
  const active=s.candidates.find(c=>c.status==='pending'&&!c.suspectedCandidateId);
  s=change(s,'CONFIRM',{id:active.id,transaction:expense(2000,1,'food','paid')});
  s=change(s,'INGEST',{...cloud,id:'notice',fingerprint:'notice',source:'notification',reliable:true});
  assert.equal(summary(s,1).availableToday,3500);assert.equal(s.transactions.length,2);
 }
});

test('no-ID AI success page then dated local detail with merchant normalization keeps one hold; new order still counts',()=>{
 for(const origin of ['wechat','alipay']){
  const cloud=live(origin,{reference:'',merchant:'测试商户（**王）',timeBasis:'page-observed-time',paymentEvidenceKey:'a'.repeat(64)});
  const bill=payment(2000,1,origin,{id:'bill',reference:`${origin}:order-1`,merchant:'扫二维码付款-给测试商户',paymentEvidenceKey:'b'.repeat(64)});
  let s=change(base(),'INGEST',[cloud,bill]);assert.equal(summary(s,1).availableToday,3500);
  for(let i=0;i<3;i++)s=change(C.restore(C.backup(s)),'INGEST',{...bill,id:'reopen-'+i,fingerprint:'reopen-'+i});
  assert.equal(summary(s,1).availableToday,3500);
  s=change(s,'INGEST',{...bill,id:'second',fingerprint:'second',reference:`${origin}:order-2`});
  assert.equal(summary(s,1).availableToday,1500,'different IDs are separate even with same amount and merchant');
 }
});

test('no-ID revisit much later stays a suspicion, not a second debit; empty merchant remains editable',()=>{
 let s=change(base(),'INGEST',live('wechat',{reference:'',timeBasis:'page-observed-time'}));
 s=change(s,'INGEST',live('wechat',{id:'later',fingerprint:'later',reference:'',timeBasis:'page-observed-time',occurredAt:'2026-11-01T12:00:00Z'}));
 assert.equal(summary(s,1).availableToday,3500);assert.ok(s.candidates.at(-1).suspectedCandidateId);
 s=change(base(),'INGEST',live('alipay',{merchant:'',reference:'',paymentEvidenceKey:''}));
 assert.equal(summary(s,1).availableToday,3500);assert.equal(s.candidates[0].merchant,'');
 s=change(s,'INGEST',live('alipay',{merchant:'',reference:'',paymentEvidenceKey:''}));
 assert.equal(summary(s,1).availableToday,3500);
});

test('manual image, uncertain results, missing native window proof and disabled channels cannot auto debit',()=>{
 for(const origin of ['wechat','alipay']){
  for(const patch of [{source:'ocr'},{captureMethod:'deepseek-fallback',fundsStatus:'unknown',evidence:undefined},{evidence:'invented'},{successful:false},{amount:null},{direction:'in'},{fundsStatus:'unknown'},{type:'complex'},{timeBasis:'screenshot-observed-time'},{status:'rejected'}]){
   const s=change(base(),'INGEST',live(origin,patch));assert.equal(summary(s,1).availableToday,5500,JSON.stringify(patch));
  }
  for(const settings of [{pageCapture:false},{pageOcr:false},{autoRecordPayments:false},{sources:[]},...(origin==='alipay'?[{alipayPageCapture:false}]:[])]){
   const s=change(change(base(),'SETTINGS',settings),'INGEST',live(origin));assert.equal(summary(s,1).availableToday,5500);
  }
 }
});

test('purpose can move AI payment to travel or own-account transfer without retaining a daily hold',()=>{
 let s=change(base(),'INGEST',live());
 const travel=change(s,'CONFIRM',{id:'ai',transaction:expense(2000,1,'trip')});
 assert.equal(summary(travel,1).availableToday,5500);assert.equal(summary(travel,1).expenses,2000);
 const own=change(s,'CONFIRM',{id:'ai',transaction:{type:'transfer',amount:2000,accountId:'cash',toAccountId:'savings',occurredAt:at(1)}});
 assert.equal(summary(own,1).availableToday,5500);assert.equal(summary(own,1).expenses,0);
 s=change(s,'DISMISS','ai');s=change(C.restore(C.backup(s)),'INGEST',live());assert.equal(summary(s,1).availableToday,5500);
});

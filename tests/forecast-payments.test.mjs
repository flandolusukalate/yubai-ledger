import test from 'node:test';
import assert from 'node:assert/strict';
import * as C from '../app/src/main/assets/web/core.mjs';
import {base,change,expense,payment,summary,plan,at} from './fixtures/forecast.mjs';

test('a 20 yuan daily payment keeps the 750 yuan plan forecast while today 55 becomes 35 for both channels',()=>{
 for(const origin of ['wechat','alipay']){
  const c=payment(2000,1,origin);let s=change(base(),'INGEST',c);
  assert.equal(summary(s,1).availableTotal,5500);assert.equal(summary(s,1).availableToday,3500);
  assert.equal(summary(s,1).forecastBalance,298000);assert.equal(summary(s,1).plannedFuture,223000);
  assert.equal(summary(s,1).plannedForecast,75000);
  s=change(s,'CONFIRM',{id:c.id,transaction:expense(2000,1)});
  assert.equal(summary(s,1).availableToday,3500);assert.equal(summary(s,1).plannedForecast,75000);
 }
});

test('unclassified overspend lowers month-end forecast once through confirmation, edit and reversal',()=>{
 let s=change(base(),'POST',expense(160000,1));
 assert.equal(summary(s).plannedForecast,75000);
 const c=payment(10000);s=change(s,'INGEST',c);
 assert.equal(summary(s).plannedForecast,70000,'Only 50 yuan of the 100 yuan payment fits the remaining daily plan');
 assert.equal(summary(s).rhythmForecast,70000,'Insufficient observations use the same corrected plan');
 assert.equal(summary(s).walletBalance,140000,'Unassigned payment must not fabricate an account transaction');
 assert.equal(summary(s).expenses,160000);
 s=change(s,'INGEST',{...c,id:C.uid(),fingerprint:C.uid()});
 assert.equal(summary(s).plannedForecast,70000,'Repeated evidence must not increase forecast deduction');
 const current=s.candidates.find(x=>x.status==='pending'&&x.reference===c.reference);
 s=change(s,'CONFIRM',{id:current.id,transaction:expense(10000,3,'food','confirmed-payment')});
 assert.equal(summary(s).plannedForecast,70000,'Purpose confirmation replaces the provisional payment');
 s=change(s,'INGEST',{...c,id:C.uid(),fingerprint:C.uid(),source:'notification',reliable:true});
 assert.equal(summary(s).plannedForecast,70000,'Evidence arriving after confirmation links the existing expense');
 assert.equal(summary(s).forecastPendingTotal,0);assert.equal(summary(s).expenses,170000);
 s=change(s,'POST',expense(20000,3,'food','confirmed-payment'));
 assert.equal(summary(s).plannedForecast,60000,'Editing updates the forecast from the actual new amount');
 s=change(s,'VOID','confirmed-payment');assert.equal(summary(s).plannedForecast,75000);
 s=change(s,'VOID','confirmed-payment');assert.equal(summary(s).plannedForecast,60000);
});

test('releasing daily capacity keeps paid cash reserved until purpose is resolved or the payment is dismissed',()=>{
 const c=payment(10000);let s=change(base(),'INGEST',c);
 s=change(s,'RELEASE_PAYMENT_HOLD',c.id);
 assert.equal(summary(s).pendingPaymentTotal,0);
 assert.equal(summary(s).forecastPendingTotal,10000,'Releasing a daily allowance hold is not a refund');
 assert.equal(summary(s).plannedForecast,65000);
 s=change(s,'UPDATE_PENDING',{id:c.id,amount:12000,merchant:'用途待核对',occurredAt:at(3)});
 assert.equal(summary(s).forecastPendingTotal,12000);assert.equal(summary(s).plannedForecast,63000);
 const transfer=change(s,'CONFIRM',{id:c.id,transaction:{id:'own-account',type:'transfer',amount:12000,accountId:'cash',toAccountId:'savings',occurredAt:at(3)}});
 assert.equal(summary(transfer).forecastPendingTotal,0);assert.equal(summary(transfer).plannedForecast,75000);
 const ignored=change(s,'DISMISS',c.id);assert.equal(summary(ignored).plannedForecast,75000);
});

test('recent pace includes provisional daily payment once and stays stable when its purpose is confirmed',()=>{
 let s=change(change(base(),'POST',expense(700,1)),'POST',expense(700,3));
 const c=payment(700,7);s=change(s,'INGEST',c);
 assert.equal(summary(s,7).basis,'recent','Three observed daily payments across seven days are enough');
 assert.equal(summary(s,7).rhythmForecast,231000,'21 yuan over 7 days leaves 23 future days at 3 yuan/day; today already exceeds that rate');
 assert.equal(summary(s,7).plannedForecast,75000,'Spending within the daily plan does not spend that plan twice');
 s=change(s,'CONFIRM',{id:c.id,transaction:expense(700,7,'food','pace-payment')});
 assert.equal(summary(s,7).rhythmForecast,231000);
 s=change(s,'POST',expense(1400,7,'food','pace-payment'));assert.equal(summary(s,7).rhythmForecast,228000);
 s=change(s,'VOID','pace-payment');assert.equal(summary(s,7).basis,'plan');assert.equal(summary(s,7).rhythmForecast,75000);
 s=change(s,'VOID','pace-payment');assert.equal(summary(s,7).rhythmForecast,228000);
});

test('unresolved past payments affect carried cash but never consume a later month daily plan',()=>{
 const c=payment(10000);let s=change(base(),'INGEST',c);
 s=change(s,'PLAN',plan('2026-12'));
 let next=C.summary(s,'2026-12','2026-12-01');
 assert.equal(next.forecastPendingTotal,10000);assert.equal(next.pendingPaymentTotal,0);
 assert.equal(next.availableToday,5322);assert.equal(next.plannedForecast,65000);
 s=C.restore(C.backup(s));assert.equal(C.summary(s,'2026-12','2026-12-01').plannedForecast,65000);
 s=change(s,'CONFIRM',{id:c.id,transaction:expense(10000,3)});
 next=C.summary(s,'2026-12','2026-12-01');assert.equal(next.forecastPendingTotal,0);assert.equal(next.plannedForecast,65000);
 assert.equal(C.summary(s,'2026-10','2026-11-03').forecastPendingTotal,0);
});

test('a known account opening balance already covers older pending payments',()=>{
 let s=change(base(),'ACCOUNT',{id:'cash',name:'日常账户',type:'wallet',opening:290000,openingDay:'2026-11-04',confirmOpening:true});
 const c=payment(10000,3,'wechat',{accountId:'cash'});s=change(s,'INGEST',c);
 assert.equal(summary(s,4).forecastPendingTotal,0,'Do not subtract a payment already included in the stated opening balance');
 assert.equal(summary(s,4).pendingPaymentTotal,10000,'Its original month budget still includes this payment');
 assert.equal(summary(s,4).plannedForecast,75000);
 s=change(s,'CONFIRM',{id:c.id,transaction:expense(10000,3)});
 assert.equal(summary(s,4).plannedForecast,75000);
});

test('failed, uncertain, incoming, screenshots, ignored and suspected duplicates never add forecast deductions',()=>{
 for(const patch of [{successful:false},{status:'rejected'},{direction:'in'},{type:'refund'},{amount:null},{source:'ocr'},{evidence:undefined}]){
  const s=change(base(),'INGEST',payment(10000,3,'wechat',patch));assert.equal(summary(s).forecastPendingTotal,0,JSON.stringify(patch));
 }
 const c=payment(10000,3,'wechat',{reference:''});let s=change(base(),'INGEST',c);
 const duplicate=payment(10000,3,'wechat',{reference:''});s=change(s,'INGEST',duplicate);
 assert.ok(s.candidates.find(x=>x.id===duplicate.id).suspectedCandidateId);assert.equal(summary(s).forecastPendingTotal,10000);
 s=change(s,'MERGE_PAYMENT',{id:duplicate.id,withId:c.id});assert.equal(summary(s).forecastPendingTotal,10000);
 s=change(s,'DISMISS',c.id);assert.equal(summary(s).forecastPendingTotal,0);
 const distinct=change(base(),'INGEST',[payment(10000),payment(10000)]);assert.equal(summary(distinct).forecastPendingTotal,20000,'Distinct transaction IDs must remain two payments');
});

test('trip, fixed and split purposes use only their assigned remaining budgets and do not inflate the daily pace',()=>{
 for(const budgetId of ['trip','fixed']){
  const c=payment(10000);let s=change(base(),'INGEST',c);
  s=change(s,'CONFIRM',{id:c.id,transaction:expense(10000,3,budgetId)});
  assert.equal(summary(s).forecastPendingTotal,0);assert.equal(summary(s).plannedForecast,75000);
  assert.equal(summary(s).D,165000);assert.equal(C.budget(s,'2026-11',budgetId).remaining,20000);
  s=change(s,'POST',expense(100,5,budgetId));s=change(s,'POST',expense(100,7,budgetId));
  assert.equal(summary(s,7).basis,'plan','Non-daily expenses cannot satisfy the daily sample count');
 }
 let s=change(base(),'POST',expense(160000,1));const c=payment(10000);s=change(s,'INGEST',c);
 s=change(s,'CONFIRM',{id:c.id,transaction:{...expense(10000,3),splits:[{amount:5000,category:'food',budgetId:'food'},{amount:5000,category:'food',budgetId:'trip'}]}});
 assert.equal(summary(s).plannedForecast,75000);assert.equal(summary(s).forecastPendingTotal,0);
});

test('linked refunds restore funds once and saved backups reproduce the same forecast and associations',()=>{
 let s=change(base(),'POST',expense(160000,1));const c=payment(10000);s=change(s,'INGEST',c);
 s=change(s,'CONFIRM',{id:c.id,transaction:expense(10000,3,'food','paid')});
 s=change(s,'POST',{id:'refund',type:'refund',amount:2000,accountId:'cash',refundOf:'paid',occurredAt:at(3)});
 assert.equal(summary(s).plannedForecast,72000);assert.equal(summary(s).forecastPendingTotal,0);
 const restored=C.restore(C.backup(s));assert.deepEqual(summary(restored),summary(s));assert.deepEqual(restored.transactions,s.transactions);assert.deepEqual(restored.candidates,s.candidates);
 s=change(s,'VOID','refund');assert.equal(summary(s).plannedForecast,70000);
});

import test from 'node:test';
import assert from 'node:assert/strict';
import * as C from '../app/src/main/assets/web/core.mjs';
// Transcribed page structure from the supplied image; recipient identity is omitted.
const fixture='14:58\n支付成功\n待收款人确认收款\n￥5.20\n完成';
test('re-sharing identical image after upgrade repairs an unknown pending amount without new record',()=>{
 const c=C.parsePayment(fixture);let s=C.reduce(C.initialState(),{type:'INGEST',value:{...c,amount:null,type:'expense',fundsStatus:undefined,recipientStatus:undefined}});const id=s.candidates[0].id;
 s=C.reduce(s,{type:'INGEST',value:{...c,id:C.uid()}});assert.equal(s.candidates.length,1);assert.equal(s.candidates[0].id,id);assert.equal(s.candidates[0].amount,520);assert.equal(s.transactions.length,0);
 s=C.reduce(s,{type:'DISMISS',value:id});s=C.reduce(s,{type:'INGEST',value:c});assert.equal(s.candidates[0].status,'dismissed');
});
test('recovering an old unknown amount still offers to link a manually recorded payment',()=>{
 const month='2026-11',occurredAt='2026-11-02T06:58:00Z';
 let s=C.reduce(C.initialState(),{type:'PLAN',value:{month,income:300000,savings:0,emergency:0,startDay:1,budgets:[{id:'food',name:'餐饮',allocated:300000,daily:true,floor:0}]}});
 const c=C.parsePayment(fixture.replace('￥5.20','5.20'),{source:'ocr',collectedAt:occurredAt});
 s=C.reduce(s,{type:'INGEST',value:{...c,amount:null,type:'expense',fundsStatus:undefined,direction:undefined}});
 s=C.reduce(s,{type:'POST',value:{type:'expense',amount:520,accountId:'cash',occurredAt,splits:[{amount:520,category:'food',budgetId:'food'}]}});
 s=C.reduce(s,{type:'INGEST',value:{...c,id:C.uid(),occurredAt:'2026-11-05T06:58:00Z'}});
 assert.equal(s.candidates.length,1);assert.equal(s.candidates[0].amount,520);assert.equal(s.candidates[0].occurredAt,occurredAt);assert.equal(s.candidates[0].suspectedId,s.transactions[0].id);assert.equal(s.transactions.length,1);
 s=C.reduce(s,{type:'MERGE',value:{id:s.candidates[0].id,transactionId:s.transactions[0].id}});assert.equal(s.transactions.length,1);assert.equal(C.summary(s,month).expenses,520);
});
test('sender payment result preserves 520 fen and distinguishes awaiting recipient',()=>{
 const c=C.parsePayment(fixture,{source:'ocr'});
 assert.equal(c.amount,520);assert.equal(c.type,'complex');assert.equal(c.direction,'out');assert.equal(c.fundsStatus,'paid_out');assert.equal(c.recipientStatus,'pending');assert.equal(c.successful,true);assert.equal(c.reliable,false);assert.equal(c.status,'pending');
 assert.equal(c.merchant,'');assert.match(c.reason,/对方.*收款/);
});
test('actual local OCR can omit currency glyph while retaining the uniquely framed decimal amount',()=>{
 for(const source of ['ocr','accessibility'])for(const amount of ['5.20','¥\n5.20']){
  const c=C.parsePayment(fixture.replace('￥5.20',amount),{source});
  assert.equal(c.amount,520);assert.equal(c.fundsStatus,'paid_out');assert.equal(c.type,'complex');assert.equal(c.reliable,false);assert.equal(c.status,'pending');
 }
 for(const amount of ['520','5.2','5.200','5.20\n100.00','¥5.20\n100.00'])assert.notEqual(C.parsePayment(fixture.replace('￥5.20',amount)).fundsStatus,'paid_out');
});
test('sender result stays pending with auto-book on; confirmed expense consumes only 520 fen once',()=>{
 const month='2026-11';let s=C.reduce(C.initialState(),{type:'PLAN',value:{month,income:300000,savings:0,emergency:0,startDay:1,budgets:[{id:'food',name:'餐饮',allocated:300000,daily:true,floor:0}]}});
 s=C.reduce(s,{type:'SETTINGS',value:{autoBook:true}});
 const c=C.parsePayment(fixture,{source:'ocr',collectedAt:'2026-11-02T06:58:00Z'});s=C.reduce(s,{type:'INGEST',value:c});assert.equal(s.candidates[0].amount,520);assert.equal(s.transactions.length,0);
 s=C.reduce(s,{type:'CONFIRM',value:{id:c.id,transaction:{type:'expense',amount:520,accountId:'cash',occurredAt:c.occurredAt,splits:[{amount:520,category:'food',budgetId:'food'}]}}});
 s=C.reduce(s,{type:'INGEST',value:c});assert.equal(s.transactions.length,1);assert.equal(C.summary(s,month).expenses,520);assert.equal(C.budget(s,month,'food').remaining,299480);
 const repeat={...c,id:C.uid(),fingerprint:'second-page-visit',source:'accessibility'};s=C.reduce(s,{type:'INGEST',value:repeat});assert.equal(s.candidates.at(-1).suspectedId,s.transactions[0].id);assert.equal(s.candidates.at(-1).status,'pending');
 s=C.reduce(s,{type:'MERGE',value:{id:repeat.id,transactionId:s.transactions[0].id}});assert.equal(s.transactions.length,1);
});
test('standalone amount is not inferred from balance, status bar, incoming receipt or missing result context',()=>{
 for(const text of ['支付成功\n¥5.20\n完成','待收款人确认收款\n¥5.20\n完成','支付成功\n待收款人确认收款\n余额¥5.20\n完成',fixture.replace('￥5.20','520'),fixture.replace('确认收款','确认退款')])assert.notEqual(C.parsePayment(text).fundsStatus,'paid_out',text);
 const ambiguous=C.parsePayment(fixture+'\n¥100.00');assert.equal(ambiguous.amount,null);assert.notEqual(ambiguous.fundsStatus,'paid_out');
 const failed=C.parsePayment(fixture+'\n支付失败');assert.equal(failed.successful,false);assert.notEqual(failed.fundsStatus,'paid_out');
 for(const text of [fixture.replace('收款人','你'),fixture+'\n处理中',fixture+'\n发送',fixture+'\n手续费',fixture.replace('支付成功\n','')+'\n支付成功'])assert.notEqual(C.parsePayment(text).fundsStatus,'paid_out');
});

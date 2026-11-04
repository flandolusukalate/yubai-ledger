import test from 'node:test';
import assert from 'node:assert/strict';
import * as C from '../app/src/main/assets/web/core.mjs';
const options={source:'notification',origin:'wechat',collectedAt:'2026-11-01T04:00:00Z'};
test('explicit completed transfer amount is preserved for purpose confirmation',()=>{
  const c=C.parsePayment('转账成功\n转账金额：20.00元\n转账单号：transfer123',options);
  assert.equal(c.amount,2000);assert.equal(c.successful,true);assert.equal(c.type,'complex');assert.equal(c.reliable,false);assert.equal(c.reference,'wechat:transfer123');assert.match(c.reason,/消费/);
});
test('outgoing transfer collected by recipient keeps explicit amount and remains pending',()=>{
  const c=C.parsePayment('微信转账\n对方已收钱\n转出金额：人民币128.50\n余额2000.00',options);
  assert.equal(c.amount,12850);assert.equal(c.successful,true);assert.equal(c.type,'complex');
  let s=C.reduce(C.initialState(),{type:'SETTINGS',value:{autoBook:true}});s=C.reduce(s,{type:'INGEST',value:c});assert.equal(s.transactions.length,0);assert.equal(s.candidates[0].status,'pending');
});
test('transfer failures cancellations and awaiting recipient never signal completed spending',()=>{
  for(const text of ['转账失败','转账已取消','转账成功 等待对方收款','转账成功 待收款','转账成功 转账处理中']){
    const c=C.parsePayment(text+'\n转账金额20.00元',options);assert.equal(c.successful,false,text);assert.equal(c.reliable,false,text);
  }
});
test('transfer labels do not choose balances, conflicting amounts, or red packet totals',()=>{
  assert.equal(C.parsePayment('转账成功\n余额3000.00\n转账金额20.00',options).amount,2000);
  assert.equal(C.parsePayment('转账成功\n转账金额20.00\n转出金额30.00',options).amount,null);
  assert.equal(C.parsePayment('转账成功\n转账金额1,200.00',options).amount,null);
  assert.equal(C.parsePayment('转账成功\n红包总额100.00\n余额3000.00',options).amount,null);
  assert.equal(C.parsePayment('已领取红包\n红包总额100.00\n他人领取额20.00',options).amount,null);
});
test('a returned transfer is not treated as a new successful expense or allowance',()=>{
  const c=C.parsePayment('微信转账 已退回\n转账金额20.00\n转账单号：transfer123',options);
  assert.equal(c.type,'complex');assert.equal(c.reliable,false);assert.equal(c.reference,'');
});
test('confirmed consumption from transfer is counted once across status and OCR updates',()=>{
  const month='2026-11';
  let s=C.reduce(C.initialState(),{type:'PLAN',value:{month,income:300000,savings:0,emergency:0,startDay:1,budgets:[{id:'food',name:'餐饮',allocated:300000,daily:true,floor:0}]}});
  const c=C.parsePayment('转账成功\n转账金额20.00\n转账单号：transfer123',options);
  s=C.reduce(s,{type:'INGEST',value:c});
  s=C.reduce(s,{type:'CONFIRM',value:{id:c.id,transaction:{type:'expense',amount:2000,accountId:'cash',occurredAt:options.collectedAt,splits:[{amount:2000,category:'food',budgetId:'food'}]}}});
  const update=C.parsePayment('微信转账\n对方已收钱\n转账金额20.00\n转账单号：transfer123',{...options,source:'ocr'});
  s=C.reduce(s,{type:'INGEST',value:update});
  assert.equal(s.transactions.length,1);assert.equal(C.summary(s,month).expenses,2000);assert.equal(s.candidates.at(-1).status,'merged');
});
test('page capture is opt-in and old backup without the preference still restores',()=>{
  const fresh=C.initialState();assert.equal(fresh.settings.pageCapture,false);
  const old=structuredClone(fresh);delete old.settings.pageCapture;assert.deepEqual(C.restore(C.backup(old)),old);
  const enabled=C.reduce(fresh,{type:'SETTINGS',value:{pageCapture:true}});assert.equal(C.restore(C.backup(enabled)).settings.pageCapture,true);
  for(const value of ['true',1,null]){const bad=structuredClone(fresh);bad.settings.pageCapture=value;assert.throws(()=>C.restore(C.backup(bad)),/页面识别/);assert.throws(()=>C.reduce(fresh,{type:'SETTINGS',value:{pageCapture:value}}),/页面识别/);}
});
test('restored page preference is not authorization or permission to auto-book accessibility evidence',()=>{
  const month='2026-11';
  let s=C.reduce(C.initialState(),{type:'PLAN',value:{month,income:300000,savings:0,emergency:0,startDay:1,budgets:[{id:'food',name:'餐饮',allocated:300000,daily:true,floor:0}]}});
  s=C.restore(C.backup(C.reduce(s,{type:'SETTINGS',value:{pageCapture:true,autoBook:true}})));
  assert.deepEqual(s.settings.sources,[],'restoring page toggle does not select a payment source');
  const page=C.parsePayment('支付成功20.00元\n商户：学校食堂',{...options,source:'accessibility'});assert.equal(page.reliable,false);
  s=C.reduce(s,{type:'INGEST',value:{...page,reliable:true}});assert.equal(s.transactions.length,0,'even incorrect reliability flag cannot auto-book page evidence');assert.equal(s.candidates[0].status,'pending');
});

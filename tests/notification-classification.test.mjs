import test from 'node:test';
import assert from 'node:assert/strict';
import * as C from '../app/src/main/assets/web/core.mjs';

const at='2026-11-01T04:00:00Z';
test('literal payment failure cannot become a successful payment or automatic deduction',()=>{
 for(const origin of ['wechat','alipay']){
  const c=C.parsePayment('付款失败\n实付20.00元',{source:'notification',origin,collectedAt:at});
  assert.equal(c.status,'rejected');assert.equal(c.successful,false);assert.equal(c.reliable,false);
  let s=C.reduce(C.initialState(),{type:'SETTINGS',value:{sources:['com.tencent.mm','com.eg.android.AlipayGphone']}});
  s=C.reduce(s,{type:'INGEST',value:c});
  assert.equal(s.transactions.length,0);assert.equal(s.candidates[0].autoRecorded,undefined);
 }
});

test('unknown notification direction survives backup and remains unbooked',()=>{
 const c={id:C.uid(),fingerprint:'unknown-notification',source:'notification',origin:'alipay',type:null,amount:null,merchant:'',reference:'alipay:unknown123',occurredAt:at,collectedAt:at,status:'pending',successful:false,reliable:false,reason:'通知中的金额、收支方向或成功状态不完整，请核对实际账单'};
 const s=C.reduce(C.initialState(),{type:'INGEST',value:c});
 assert.equal(s.transactions.length,0);assert.equal(s.candidates[0].type,null);
 const restored=C.restore(C.backup(s));assert.deepEqual(restored,s);
 assert.equal(C.summary(restored,'2026-11','2026-11-01').pendingPaymentTotal,0);
});

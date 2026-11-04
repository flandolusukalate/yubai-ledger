import test from 'node:test';
import assert from 'node:assert/strict';
import {paymentCounterparty,similarPaymentPages} from '../app/src/main/assets/web/payment-evidence.mjs';
const common={evidence:'wechat-custom-paid-page-v1',origin:'wechat',source:'accessibility',captureMethod:'accessibility-window-ocr',successful:true,direction:'out',fundsStatus:'paid_out',amount:200};
const result={...common,merchant:'ABQ_测试晴(**王)',timeBasis:'page-observed-time',reference:'',occurredAt:'2026-11-01T04:00:00Z',collectedAt:'2026-11-01T04:00:00Z'};
const bill={...common,merchant:'扫二维码付款-给AQ_测试睛',timeBasis:'page-local-time-book-zone',reference:'wechat:actual00001',occurredAt:'2026-11-01T03:45:00Z',collectedAt:'2026-11-01T04:01:00Z'};
test('known labels and masked legal-name suffix normalize; ordinary branches stay distinct',()=>{
 assert.equal(paymentCounterparty('扫二维码付款-给测试商户'),paymentCounterparty('测试商户（**王）'));
 assert.notEqual(paymentCounterparty('测试商户（东店）'),paymentCounterparty('测试商户（西店）'));
});
test('minor OCR counterparty variation is symmetric and bounded to paired result/bill evidence',()=>{
 assert.ok(similarPaymentPages(result,bill));assert.ok(similarPaymentPages(bill,result));
 for(const patch of [{amount:201},{origin:'alipay'},{successful:false},{direction:'in'},{fundsStatus:'unknown'},{source:'ocr'},{merchant:'ABQ_其他商家'},{timeBasis:'page-observed-time'},{reference:''},{collectedAt:'2026-11-01T04:06:00Z'},{occurredAt:'2026-11-01T03:29:00Z'},{occurredAt:'2026-11-01T04:02:00Z'},{collectedAt:'bad'}])assert.equal(similarPaymentPages(result,{...bill,...patch}),false,JSON.stringify(patch));
 assert.equal(similarPaymentPages({...result,merchant:'测试小店'},{...bill,merchant:'测试大店'}),false);
});

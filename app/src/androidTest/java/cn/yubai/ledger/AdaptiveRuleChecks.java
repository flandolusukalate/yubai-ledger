package cn.yubai.ledger;

import org.json.JSONObject;
import org.json.JSONArray;
import java.time.ZoneId;

/** Synthetic semantic layout changes through the same parser used by live capture. */
final class AdaptiveRuleChecks {
    private int checks;
    private void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;}
    private interface Checked{void run()throws Exception;}
    private void rejects(Checked action,String label)throws Exception{boolean failed=false;try{action.run();}catch(IllegalArgumentException e){failed=true;}check(failed,label);}
    private JSONObject line(String text,double x,double y,double w)throws Exception{return CustomRuleVerificationRunner.rect(x,y,w,.03).put("text",text);}
    private JSONObject doc()throws Exception{return new JSONObject().put("width",1000).put("height",2200).put("lines",new JSONArray().put(line("支付成功",.3,.10,.4)).put(line("测试餐厅",.3,.25,.4)).put(line("实付金额 ¥20.00",.2,.35,.6)).put(line("完成",.3,.85,.4)));}
    private JSONObject rule(JSONObject doc)throws Exception{return new JSONObject().put("id","adaptive-test").put("version",1).put("revision",1).put("name","语义测试").put("origin","wechat").put("adaptive",true).put("locators",new JSONObject()).put("enabled",false).put("aspect",1000.0/2200).put("fields",PaymentFieldLocator.suggest(doc).getJSONObject("fields")).put("testedHash","").put("windowClass","");}
    private JSONObject evaluate(JSONObject rule,JSONObject doc)throws Exception{return CustomPaymentRule.evaluate(rule,doc,System.currentTimeMillis(),"adaptive-test",ZoneId.of("Asia/Shanghai"));}
    static int run()throws Exception{return new AdaptiveRuleChecks().verify();}
    private int verify()throws Exception{
        JSONObject doc=doc(),draft=rule(doc),learned=PaymentFieldLocator.learn(draft,doc);
        check(draft.getJSONObject("fields").length()==3,"one screenshot suggests amount, merchant and success");
        JSONObject huge=doc();huge.getJSONArray("lines").getJSONObject(2).put("h",.5);
        check(!PaymentFieldLocator.suggest(huge).getJSONObject("fields").has("amount"),"oversized OCR block is not applied as a valid automatic mark");
        check(learned.getJSONObject("locators").getString("amount").equals("paid-label"),"paid label is learned, not numeric value");
        check(evaluate(learned,doc).getLong("amount")==2000,"learned screenshot uses existing parser");
        JSONObject moved=new JSONObject(doc.toString()).put("height",1900);JSONArray movedLines=moved.getJSONArray("lines");
        for(int i=0;i<movedLines.length();i++){JSONObject l=movedLines.getJSONObject(i);l.put("y",l.getDouble("y")+.05);}
        movedLines.getJSONObject(2).put("text","实付金额 ¥32.50");
        JSONObject changed=evaluate(learned,moved);
        check(changed.getLong("amount")==3250&&changed.getString("customRuleHash").equals(CustomPaymentRule.signature(learned)),"moved layout and new amount retain tested rule identity");
        JSONObject fixed=new JSONObject(draft.toString()).put("adaptive",false).put("locators",new JSONObject());
        rejects(()->evaluate(fixed,moved),"legacy fixed layout guard stays intact");
        JSONObject prices=new JSONObject(doc.toString());prices.getJSONArray("lines").put(line("原价 ¥99.00",.2,.55,.6)).put(line("余额 ¥999.00",.2,.65,.6));
        check(evaluate(learned,prices).getLong("amount")==2000,"large original price and balance do not replace actual paid amount");
        JSONObject split=doc();split.getJSONArray("lines").getJSONObject(2).put("text","实付金额").put("x",.05).put("w",.2);
        split.getJSONArray("lines").put(line("¥18.80",.50,.345,.2));
        JSONObject splitLearned=PaymentFieldLocator.learn(rule(split),split);
        check(evaluate(splitLearned,split).getLong("amount")==1880,"same-row label and amount with imperfect y alignment join in semantic order");
        JSONObject ambiguous=new JSONObject(doc.toString());ambiguous.getJSONArray("lines").put(line("实付金额 ¥25.00",.2,.65,.6));
        check(!PaymentFieldLocator.suggest(ambiguous).getJSONObject("fields").has("amount"),"multiple actual paid amounts are not guessed");
        rejects(()->evaluate(learned,ambiguous),"runtime ambiguity cannot debit");
        JSONObject pending=CustomPaymentRule.reviewCandidate(learned,ambiguous,System.currentTimeMillis(),"visit-one",ZoneId.of("Asia/Shanghai"));
        check(pending!=null&&pending.isNull("amount")&&!pending.getBoolean("successful"),"ambiguous paid page is review-only without zero amount or automatic debit");
        check(CustomPaymentRule.reviewCandidate(learned,ambiguous,System.currentTimeMillis()+10000,"visit-one",ZoneId.of("Asia/Shanghai")).getString("fingerprint").equals(pending.getString("fingerprint")),"repeat uncertain page has stable identity");
        check(!CustomPaymentRule.reviewCandidate(learned,ambiguous,System.currentTimeMillis()+10000,"another-visit",ZoneId.of("Asia/Shanghai")).getString("fingerprint").equals(pending.getString("fingerprint")),"no-ID same-amount visits are not silently merged as one transaction");
        for(String status:new String[]{"待支付","支付失败","收款成功","订单提交成功"}){
            JSONObject failed=doc();failed.getJSONArray("lines").getJSONObject(0).put("text",status);
            check(!PaymentFieldLocator.suggest(failed).getJSONObject("fields").has("success"),"non-payment status is not suggested");
            rejects(()->evaluate(learned,failed),"non-payment status cannot debit");
        }
        JSONObject chat=new JSONObject(ambiguous.toString());chat.getJSONArray("lines").put(line("按住说话",.2,.7,.6));
        rejects(()->CustomPaymentRule.reviewCandidate(learned,chat,System.currentTimeMillis(),"chat",ZoneId.of("Asia/Shanghai")),"chat or sensitive content never generates review evidence");
        JSONObject corrected=doc();corrected.getJSONArray("lines").put(line("真实餐厅",.3,.43,.4));JSONObject correction=rule(corrected);
        check(!correction.getJSONObject("fields").has("merchant"),"two different nearby names require correction");
        correction.getJSONObject("fields").put("merchant",CustomRuleVerificationRunner.rect(.3,.43,.4,.03));
        JSONObject correctedRule=PaymentFieldLocator.learn(correction,corrected);
        check(correctedRule.getJSONObject("locators").getString("merchant").equals("merchant-below"),"user correction learns relationship below the amount");
        check(evaluate(correctedRule,corrected).getString("merchant").equals("真实餐厅"),"corrected merchant used");
        check(!CustomPaymentRule.signature(correctedRule).equals(CustomPaymentRule.signature(learned)),"changed relationship invalidates proof");
        JSONObject negative=doc();negative.getJSONArray("lines").getJSONObject(2).put("text","−14.90");
        JSONObject negativeRule=PaymentFieldLocator.learn(rule(negative),negative);
        check(evaluate(negativeRule,negative).getLong("amount")==1490,"unique outgoing amount can be learned");
        JSONObject forged=new JSONObject(learned.toString());forged.getJSONObject("locators").put("amount","largest-number");
        rejects(()->CustomPaymentRule.validate(forged),"unsupported locator cannot enter saved configuration");
        JSONObject legacy=new JSONObject(fixed.toString());legacy.remove("adaptive");legacy.remove("locators");
        check(CustomPaymentRule.signature(legacy).equals(CustomPaymentRule.signature(fixed)),"legacy signatures survive optional inactive mode fields");
        return checks;
    }
}

package cn.yubai.ledger;

import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** Only an authorized captured-window task may promote a cloud interpretation. */
final class WindowAiPayment {
    static JSONObject qualify(JSONObject candidate,JSONObject document,boolean resultWindow) throws Exception {
        JSONObject c=new JSONObject(candidate.toString());
        String origin=c.optString("origin"),raw=DeepSeekReceipt.text(document);
        if(!Set.of("wechat","alipay").contains(origin)||!DeepSeekReceipt.safeImage(document,true)
            ||!c.optBoolean("successful")||!c.optString("direction").equals("out")
            ||!c.optString("type").equals("expense")||c.isNull("amount")||c.optLong("amount")<=0
            ||Pattern.compile("支付失败|付款失败|交易失败|未成功|不成功|未付款|交易关闭|交易取消|已取消|已关闭|处理中|等待付款|待付款|待(?:你|您|本人|自己|我).*收款|退回|退款成功|收款成功|已到账|还款成功|充值成功|提现成功").matcher(raw).find())return c;
        // A detail page needs its original time. An immediate result alone may use observation time.
        boolean detail=Pattern.compile("账单详情|转账详情|当前状态|交易时间|转账时间|支付时间").matcher(raw).find();
        if(!c.optString("timeBasis").equals("page-local-time-book-zone")){
            if(detail||!resultWindow)return c;
            c.put("timeBasis","page-observed-time");
        }
        c.put("source","accessibility").put("captureMethod","accessibility-window-ai")
            .put("evidence",origin+"-ai-paid-page-v1").put("fundsStatus","paid_out")
            .put("reason","高清本地识别未通过，DeepSeek 已识别明确付款；正在统一查重，用途可稍后补充。");
        if(!c.optString("reference").isEmpty())c.put("referenceLayout","bounded-lines-v1");
        return c;
    }
}

package cn.yubai.ledger;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Only complete sender result structures are accepted. Raw OCR and images are never persisted. */
final class ScreenOcrParser {
    static JSONObject parse(String text,long now,String session) throws Exception {
        return parse(text,now,session,false);
    }
    static JSONObject parse(String text,long now,String session,boolean knownResultWindow) throws Exception {
        if(text==null||text.length()>8192)return null;
        List<String> lines=new ArrayList<>();
        for(String line:java.text.Normalizer.normalize(text,java.text.Normalizer.Form.NFKC).split("\\r?\\n")){
            line=line.trim();
            // Observed on the physical device: ML Kit reads the large yuan glyph
            // as '*'. This only normalizes a standalone two-decimal money token;
            // the strict sender/merchant-result parser still supplies its role.
            if(line.matches("\\*[ \\t]*[0-9]+\\.[0-9]{2}"))line="¥"+line.substring(1).trim();
            if(line.equals("果单详情"))line="账单详情"; // Observed ML Kit glyph confusion; footer role only.
            // Observed in the user's 5.20 receipt, at both native and 1440px
            // resolution. Exact glyph correction only; all structural, outgoing
            // payment and amount gates below still apply (never fuzzy-match status).
            if(line.equals("支付戌功"))line="支付成功";
            if(!line.isEmpty())lines.add(line);
        }
        JSONObject c=PageCaptureService.parsePage("com.tencent.mm",lines,now,session);
        if(c==null&&knownResultWindow)c=WechatMerchantResult.parse(lines,now,true);
        boolean detail=c!=null&&c.optString("evidence").equals("wechat-paid-transfer-pending-detail-v1");
        if(c==null||!c.optBoolean("successful")||c.isNull("amount")||c.getLong("amount")<=0)return null;
        String evidence=c.optString("evidence");
        if(!detail&&!evidence.equals("wechat-paid-transfer-result-v1")&&!evidence.equals("wechat-paid-merchant-result-v1"))return null;
        int start=Math.max(lines.indexOf("支付成功"),lines.indexOf("付款成功")),end=lines.indexOf("完成");
        if(!detail&&(start<0||end<=start))return null;
        // Exclude status-bar clock/battery. Include recipient content only in a digest,
        // so a repeated receipt across sessions is flagged without saving their name.
        String key=WechatPaymentPage.evidenceKey(c,lines);
        c.put("source","screen_ocr");c.put("reliable",false);c.put("screenEvidenceKey",key);
        c.put("fingerprint",PaymentListener.digest("screen_ocr|"+session+"|"+key+"|"+(detail?c.optString("occurredAt"):"")));
        c.put("reason",detail?"窗口显示付款方已转出、对方待收款；已提取页面转账时间，请核对用途。":"本地窗口 OCR 识别的付款成功页；缺少交易时间时按观察时间记录，请核对用途及真实交易时间。");
        return c;
    }
}

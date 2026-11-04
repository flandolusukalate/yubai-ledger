package cn.yubai.ledger;

import java.util.List;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** Payment semantics shared by readable nodes and OCR, independent of capture permissions. */
final class WechatPaymentPage {
    static String evidenceKey(JSONObject c,List<String> lines) throws Exception {
        boolean detail=c.optString("evidence").equals("wechat-paid-transfer-pending-detail-v1");
        String recipient="";
        for(String line:lines)if(line.matches(detail?"待.{1,160}收款":"待.{1,160}确认收款"))
            recipient=line.replaceFirst("(?:确认)?收款$","确认收款").replaceAll("\\s+","");
        return PaymentListener.digest((detail?"wechat-paid-transfer-result-v1":c.optString("evidence"))+"|"+c.getLong("amount")+"|"+recipient+"|"+c.optString("merchant"));
    }
    static JSONObject accessible(JSONObject c,List<String> lines,String session) throws Exception {
        if(c==null)return null;
        String key=evidenceKey(c,lines);
        c.put("source","accessibility").put("paymentEvidenceKey",key);
        c.put("fingerprint",PaymentListener.digest("accessibility|wechat|page|"+session+"|"+key+"|"+(c.optString("evidence").equals("wechat-paid-transfer-pending-detail-v1")?c.optString("occurredAt"):"")));
        return c;
    }
    static JSONObject pendingDetail(List<String> lines,long now) throws Exception {
        String text=String.join("\n",lines),compact=text.replaceAll("\\s+","");
        if(Pattern.compile("失败|已取消|已关闭|处理中|待支付|已退回|退款|余额|原价|优惠|手续费|红包|验证码|密码|口令").matcher(text).find())return null;
        for(String line:lines)if(line.matches("发送|按住说话|聊天信息|通讯录|朋友圈"))return null;
        if(!compact.contains("对方未收款")||!compact.contains("将退还给你")||!compact.contains("提醒对方收款")||!compact.contains("账单详情"))return null;
        int waiting=-1,waitingCount=0,amountAt=-1,count=0,dateAt=-1;String amount="";
        for(int i=0;i<lines.size();i++){
            String line=lines.get(i);
            if(line.matches("待.{1,160}收款")){waiting=i;waitingCount++;if(line.matches("待(?:你|您|本人|自己|我).*"))return null;}
            if(line.startsWith("转账时间"))dateAt=i;
            if(line.matches("[¥￥][ \\t]*[0-9]+(?:\\.[0-9]{1,2})?")||line.matches("[0-9]+\\.[0-9]{2}")){count++;amountAt=i;amount=line.replaceFirst("^[¥￥][ \\t]*","");}
            else if(Pattern.compile("[¥￥][ \\t]*[0-9]").matcher(line).find())return null;
        }
        if(waitingCount!=1||waiting>=12||count!=1||amountAt<=waiting||amountAt>waiting+3||dateAt<=amountAt)return null;
        String afterDate=String.join(" ",lines.subList(dateAt,Math.min(lines.size(),dateAt+3)));
        var matcher=Pattern.compile("转账时间[ :：]*(20[0-9]{2}年[0-9]{1,2}月[0-9]{1,2}日[ \\t]*[0-9]{2}:[0-9]{2}:[0-9]{2})").matcher(afterDate);
        if(!matcher.find())return null;
        String occurred;try{occurred=LocalDateTime.parse(matcher.group(1).replaceAll("\\s+",""),DateTimeFormatter.ofPattern("uuuu年M月d日HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)).atZone(ZoneId.systemDefault()).toInstant().toString();}catch(Exception invalid){return null;}
        long cents=new java.math.BigDecimal(amount).movePointRight(2).longValueExact();if(cents<=0||cents>1_000_000_000_000L)return null;
        return new JSONObject().put("id",java.util.UUID.randomUUID().toString()).put("origin","wechat").put("type","complex").put("amount",cents)
            .put("status","pending").put("successful",true).put("direction","out").put("fundsStatus","paid_out").put("recipientStatus","pending")
            .put("evidence","wechat-paid-transfer-pending-detail-v1").put("reference","").put("merchant","").put("category",JSONObject.NULL)
            .put("occurredAt",occurred).put("collectedAt",Instant.ofEpochMilli(now).toString()).put("timeBasis","page-local-time-device-zone");
    }
}

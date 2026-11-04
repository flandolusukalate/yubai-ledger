package cn.yubai.ledger;

import org.json.JSONObject;
import org.json.JSONArray;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Alipay outgoing receipts only. Currency/amount role and successful status are mandatory. */
final class AlipayPaymentPage {
    static final String EVIDENCE="alipay-paid-page-v1";
    private static final Pattern MONEY=Pattern.compile("^(?:(实付金额|实际支付|实付款|实付|付款金额|支付金额)[:：]?)?(?:人民币)?(-?[¥￥*]?-?[0-9]+(?:\\.[0-9]{1,2})?)元?$");
    private static String text(JSONObject l){return l.optString("text").replace('−','-').replace('–','-').trim();}
    private static String value(List<JSONObject> lines,String label){
        for(JSONObject l:lines){var match=Pattern.compile("^(?:"+label+")[:：]?\\s*(.*)$").matcher(text(l));if(!match.matches())continue;
            if(!match.group(1).isBlank())return match.group(1).trim();
            JSONObject nearest=null;double distance=Double.MAX_VALUE;
            for(JSONObject n:lines){double dy=Math.abs(n.optDouble("y")-l.optDouble("y"));
                if(n==l||dy>.018||n.optDouble("x")<l.optDouble("x")+l.optDouble("w")-.02)continue;
                double d=dy*10+n.optDouble("x")-l.optDouble("x");if(d<distance){distance=d;nearest=n;}}
            if(nearest!=null)return text(nearest);
        }return "";
    }
    static JSONObject parse(JSONObject doc,long now,String session,String window,ZoneId zone) throws Exception {
        if(!AlipayCaptureWindow.allowed(window))return null;
        JSONArray input=doc.getJSONArray("lines");if(input.length()>200)return null;
        List<JSONObject> lines=new ArrayList<>();int length=0;
        for(int i=0;i<input.length();i++){JSONObject l=input.getJSONObject(i);length+=text(l).length();if(length>8192)return null;lines.add(l);}
        lines.sort(Comparator.comparingDouble((JSONObject l)->l.optDouble("y")).thenComparingDouble(l->l.optDouble("x")));
        if(AlipayCaptureWindow.MSP.equals(window))return AlipayMspReceipt.parse(lines,now,session);
        String all=String.join("\n",lines.stream().map(AlipayPaymentPage::text).collect(java.util.stream.Collectors.toList()));
        if(Pattern.compile("支付失败|付款失败|交易失败|未成功|不成功|交易关闭|已取消|处理中|待付款|待支付|退款成功|退款到账|已退款|已退回|收款成功|到账成功|收入金额|验证码|密码|口令|充值|提现|还款|借款|代付|报销|红包|转账").matcher(all).find())return null;
        String reference=PaymentReference.alipay(lines);
        boolean bill=lines.stream().anyMatch(l->text(l).matches("[く<〈‹＜]?\\s*账单详情"))&&all.contains("支付时间")&&(all.contains("付款方式")||!reference.isEmpty());
        JSONObject status=null;
        // Observed local OCR can read 成 as 戌 on the bill's four-character
        // status. Accept this exact variant only with the complete bill schema.
        for(JSONObject l:lines)if(text(l).matches(bill?"交易[成戌]功|支付成功|付款成功":"支付成功|付款成功")&&l.optDouble("y")<.5){if(status!=null)return null;status=l;}
        if(status==null)return null;
        boolean done=lines.stream().anyMatch(l->text(l).equals("完成"));
        if(!bill&&!done)return null;
        JSONObject amountLine=null;String amountText="";boolean explicit=false;
        for(JSONObject l:lines){String token=text(l).replaceAll("\\s+","");var m=MONEY.matcher(token);
            if(!m.matches()||l.optDouble("y")>.5)continue;
            // Bill amount is a negative debit directly above its successful status.
            // Result amount needs a currency/paid label near the payment-success header.
            boolean role=bill?token.contains("-")&&l.optDouble("y")<status.optDouble("y")&&status.optDouble("y")-l.optDouble("y")<.15:
                (m.group(1)!=null||token.matches(".*[¥￥*].*"))&&Math.abs(l.optDouble("y")-status.optDouble("y"))<.22;
            if(!role)continue;if(amountLine!=null)return null;amountLine=l;amountText=m.group(2);explicit=m.group(1)!=null;
        }
        if(amountLine==null)return null;
        if(!bill&&!explicit&&Pattern.compile("原价|订单金额|商品金额|优惠金额|优惠[¥￥0-9]|实付").matcher(all).find())return null;
        long amount=new BigDecimal(amountText.replaceAll("[-¥￥*]","")).movePointRight(2).longValueExact();if(amount<=0||amount>1_000_000_000_000L)return null;
        String merchant="";double closest=Double.MAX_VALUE;
        for(JSONObject l:lines){String t=text(l);double gap=amountLine.optDouble("y")-(l.optDouble("y")+l.optDouble("h"));
            if(gap<-.005||gap>.12||l.optDouble("w")<.1||t.length()>120||!t.matches(".*[\\p{L}].*")||Pattern.compile("成功|账单详情|扣费解读|支付宝|返回|完成|付款|支付|金额|余额|积分|奖励|优惠").matcher(t).find())continue;
            if(gap<closest){closest=gap;merchant=t;}}
        if(merchant.isEmpty())merchant=value(lines,"收款方全称|收款方|商户名称|商家名称");
        if(merchant.length()>120||Pattern.compile("密码|验证码|银行卡|储蓄卡|信用卡|[¥￥]").matcher(merchant).find())merchant="";
        String occurred=Instant.ofEpochMilli(now).toString(),basis="page-observed-time";
        if(bill){
            String stamp=value(lines,"支付时间").replace('年','-').replace('月','-').replace("日","").replace('/','-').trim();
            if(!stamp.matches("20[0-9]{2}-[0-9]{1,2}-[0-9]{1,2}\\s+[0-9]{2}:[0-9]{2}(?::[0-9]{2})?"))return null;
            stamp=stamp.replaceAll("\\s+"," ");if(stamp.length()-stamp.lastIndexOf(' ')==6)stamp+=":00";
            occurred=LocalDateTime.parse(stamp,DateTimeFormatter.ofPattern("uuuu-M-d HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)).atZone(zone).toInstant().toString();
            if(Instant.parse(occurred).toEpochMilli()>now+300_000)return null;basis="page-local-time-book-zone";
        }
        return candidate(amount,merchant,reference,occurred,basis,now,session);
    }
    static JSONObject candidate(long amount,String merchant,String reference,String occurred,String basis,long now,String session)throws Exception {
        String key=PaymentListener.digest(EVIDENCE+"|"+amount+"|"+merchant);
        return new JSONObject().put("id",java.util.UUID.randomUUID().toString()).put("origin","alipay").put("source","accessibility").put("captureMethod","accessibility-window-ocr")
            .put("evidence",EVIDENCE).put("amount",amount).put("merchant",merchant).put("type","expense").put("status","pending").put("successful",true).put("reliable",false)
            .put("direction","out").put("fundsStatus","paid_out").put("reference",reference.isEmpty()?"":"alipay:"+reference).put("referenceLayout",reference.isEmpty()?"":"bounded-lines-v1")
            .put("occurredAt",occurred).put("collectedAt",Instant.ofEpochMilli(now).toString()).put("timeBasis",basis).put("paymentEvidenceKey",key)
            .put("fingerprint",PaymentListener.digest("alipay-window|"+session+"|"+key+"|"+reference+"|"+occurred))
            .put("reason","支付宝付款已提取，正在统一查重；用途可稍后修改");
    }
}

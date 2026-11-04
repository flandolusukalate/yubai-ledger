package cn.yubai.ledger;

import org.json.JSONObject;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/** The shared MSP cashier is only a receipt after its complete result structure is visible. */
final class AlipayMspReceipt {
    private static String text(JSONObject l){return l.optString("text").replace('−','-').replace('–','-').replaceAll("\\s+","");}
    private static final Pattern MONEY=Pattern.compile("-?[¥￥Y]([0-9]+(?:\\.[0-9]{1,2})?)");
    private static String moneyToken(JSONObject line){
        String token=text(line);
        // Observed OCR drops the horizontal strokes of ¥ on the small right
        // column. Accept only that prefix there; never repair amount digits.
        if(line.optDouble("x")>=.6){
            if(token.startsWith("\\"))token="¥"+token.substring(1);
            else if(token.startsWith("-\\"))token="-¥"+token.substring(2);
        }
        return token;
    }
    private static Long money(JSONObject line){
        var m=MONEY.matcher(moneyToken(line));if(!m.matches())return null;
        try{return new BigDecimal(m.group(1)).movePointRight(2).longValueExact();}catch(Exception e){return null;}
    }
    private static boolean blurredSecondary(JSONObject line){return line.optDouble("x")>=.6&&moneyToken(line).matches("[¥￥Y][0-9Oo]+\\.[0-9Oo]{2}");}
    private static boolean agreesWith(JSONObject secondary,long independentlyKnown){
        Long exact=money(secondary);if(exact!=null)return exact==independentlyKnown;
        if(!blurredSecondary(secondary))return false;
        String observed=moneyToken(secondary).substring(1),expected=BigDecimal.valueOf(independentlyKnown,2).toPlainString();
        if(observed.length()!=expected.length())return false;
        // Compare only against a value established by the clear headline and
        // exact discount rows. Never derive an amount by replacing OCR letters.
        for(int i=0;i<expected.length();i++)if(observed.charAt(i)!=expected.charAt(i)&&!(expected.charAt(i)=='0'&&(observed.charAt(i)=='O'||observed.charAt(i)=='o')))return false;
        return true;
    }
    static JSONObject parse(List<JSONObject> lines,long now,String session)throws Exception {
        JSONObject success=null,boundary=null,method=null;boolean done=false;
        for(JSONObject l:lines){String t=text(l);
            if(Pattern.compile("密码|验证码|口令|确认付款|确认支付|立即付款|立即支付").matcher(t).find())return null;
            if(t.matches("[✓✔√☑○◎Oo]?支付成功")&&l.optDouble("y")<.2){if(success!=null)return null;success=l;}
            if(t.contains("点击给商家对账")&&l.optDouble("y")<.55){if(boundary!=null)return null;boundary=l;}
            if(t.equals("付款方式")){if(method!=null)return null;method=l;}
            if(t.equals("完成")&&l.optDouble("y")>.6)done=true;
        }
        if(success==null||method==null||!done||method.optDouble("y")<=success.optDouble("y")||method.optDouble("y")>=.5||boundary!=null&&method.optDouble("y")>=boundary.optDouble("y"))return null;
        // Reconciliation is an optional control. Without it, the payment-method
        // row ends the receipt; promotional rewards below it are not payments.
        double receiptEnd=boundary==null?method.optDouble("y")+method.optDouble("h")+.01:boundary.optDouble("y");
        for(JSONObject l:lines)if(l.optDouble("y")<receiptEnd&&Pattern.compile("失败|待付款|待支付|处理中|取消|关闭|退款|退回|收款成功|到账成功|充值|提现|还款|借款|代付|转账|红包").matcher(text(l)).find())return null;
        JSONObject headline=null,original=null;long discount=0;
        for(JSONObject l:lines){Long amount=money(l);if(amount==null&&!blurredSecondary(l))continue;
            double y=l.optDouble("y"),x=l.optDouble("x");
            if(y<=success.optDouble("y")||y>=method.optDouble("y"))continue;
            if(x>.15&&x<.6&&y-success.optDouble("y")<.16&&text(l).matches("[¥￥][0-9].*")){
                if(headline!=null)return null;headline=l;
            }else if(x>=.6){
                if(text(l).startsWith("-"))discount=Math.addExact(discount,amount);
                else {if(original!=null)return null;original=l;}
            }else return null;
        }
        if(headline==null||original==null||headline.optDouble("y")>=original.optDouble("y"))return null;
        long paid=money(headline);
        if(paid<=0||paid>1_000_000_000_000L||!agreesWith(original,Math.addExact(paid,discount)))return null;
        String merchant="";
        for(JSONObject l:lines)if(l.optDouble("x")<.6&&Math.abs(l.optDouble("y")-original.optDouble("y"))<.018){
            String t=text(l);if(!t.matches(".*[\\p{L}].*")||t.length()>120||Pattern.compile("金额|支付|优惠|付款|余额|银行|卡|成功").matcher(t).find())return null;
            if(!merchant.isEmpty())return null;merchant=l.optString("text").trim();
        }
        if(merchant.isEmpty())return null;
        return AlipayPaymentPage.candidate(paid,merchant,"",Instant.ofEpochMilli(now).toString(),"page-observed-time",now,session);
    }
}

package cn.yubai.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** Completed outgoing merchant receipt; never infer payment from the largest number. */
final class WechatMerchantResult {
    private static final String NUMBER="(?:人民币\\s*)?[¥￥]?\\s*(?:[0-9]+|[1-9][0-9]{0,2}(?:,[0-9]{3})+)(?:\\.[0-9]{1,2})?\\s*元?";
    private static final String PAID="实付金额|实际支付|实付款|实付|付款金额|支付金额";
    private static final String OTHER="订单金额|商品金额|原价|优惠金额|优惠|已优惠|立减|折扣|零钱余额|余额|手续费";
    private static final String MERCHANT="商户名称|商家名称|商户|商家|收款方|收款商户";
    private static String value(List<String> lines,int i,String labels,boolean[] used){
        String line=lines.get(i);
        var m=Pattern.compile("^(?:"+labels+")(?:[ :：]+(.*)|(?=[¥￥0-9])(.+)|$)").matcher(line);
        if(!m.matches())return null;
        used[i]=true;
        String inline=m.group(1)!=null?m.group(1):m.group(2);
        if(inline!=null&&!inline.isEmpty())return inline.trim();
        if(i+1>=lines.size())return "";
        used[i+1]=true;
        String next=lines.get(i+1);
        if(next.matches("[¥￥]")&&i+2<lines.size()){used[i+2]=true;return next+lines.get(i+2);}
        return next;
    }
    private static Long cents(String token){
        if(token==null||!token.matches(NUMBER))return null;
        try{long value=new BigDecimal(token.replaceAll("人民币|[¥￥元,\\s]","")).movePointRight(2).longValueExact();return value>0&&value<=1_000_000_000_000L?value:null;}catch(Exception invalid){return null;}
    }
    static JSONObject parse(List<String> lines,long now,boolean knownResultWindow) throws Exception {
        if(lines==null||lines.size()>200)return null;
        String text=String.join("\n",lines);
        if(text.length()>8192||Pattern.compile("失败|已取消|已关闭|处理中|待支付|退款|退回|红包|转账|充值|提现|还款|借款|代付|报销|待.{0,160}(?:收款|领取)|收入|收款成功|收款到账|已收款|已收钱|验证码|密码|口令").matcher(text).find())return null;
        int header=-1,done=-1;
        for(int i=0;i<lines.size();i++){
            String line=lines.get(i);
            if(line.matches("发送|按住说话|聊天信息|通讯录|朋友圈"))return null;
            if(i<12&&line.matches("支付成功|付款成功"))header=i;
            if(line.equals("完成"))done=i;
        }
        if(header<0||done<=header)return null;
        boolean[] used=new boolean[lines.size()];List<Long> amounts=new ArrayList<>();
        String merchant="",reference="",occurred=Instant.ofEpochMilli(now).toString(),timeBasis="page-observed-time";
        boolean explicit=false;
        for(int i=0;i<lines.size();i++){
            if(used[i])continue;
            String v=value(lines,i,PAID,used);
            if(v!=null){Long n=cents(v);if(n==null||i<=header||i>=done)return null;amounts.add(n);explicit=true;continue;}
            v=value(lines,i,OTHER,used);if(v!=null){if(!v.matches(NUMBER))return null;continue;}
            v=value(lines,i,MERCHANT,used);
            if(v!=null){if(i<=header||i>=done||v.isEmpty()||v.length()>80||v.matches(".*[¥￥].*|完成|支付成功|付款成功|付款方式|微信支付")||cents(v)!=null)return null;if(!merchant.isEmpty()&&!merchant.equals(v))return null;merchant=v;continue;}
            v=value(lines,i,"交易单号|交易号",used);
            if(v!=null){if(!v.matches("[A-Za-z0-9]{8,64}"))return null;if(!reference.isEmpty()&&!reference.equals("wechat:"+v))return null;reference="wechat:"+v;continue;}
            // Merchant order IDs are not interchangeable with WeChat transaction IDs.
            v=value(lines,i,"商户单号|商家订单号|订单号",used);if(v!=null)continue;
            v=value(lines,i,"支付时间|交易时间",used);
            if(v!=null){
                try{
                    String normalized=v.replace('年','-').replace('月','-').replace("日","").replace('/','-').trim();
                    if(normalized.length()==16)normalized+=":00";
                    String timestamp=LocalDateTime.parse(normalized,DateTimeFormatter.ofPattern("uuuu-M-d HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)).atZone(ZoneId.systemDefault()).toInstant().toString();
                    if(Instant.parse(timestamp).toEpochMilli()>now+300_000)return null;
                    if(timeBasis.equals("page-local-time-device-zone")&&!occurred.equals(timestamp))return null;
                    occurred=timestamp;timeBasis="page-local-time-device-zone";
                }catch(Exception invalid){return null;}
            }
        }
        for(int i=0;i<lines.size();i++){
            if(used[i])continue;
            String token=lines.get(i);Long n=cents(token);
            // Unlabelled currency / two-decimal values may be the prominent payment
            // total, but only near the success header in a verified result window.
            if(n!=null&&(token.matches(".*[¥￥元].*")||token.matches("[0-9,]+\\.[0-9]{2}"))){
                if(!knownResultWindow||i<=header||i>header+6||i>=done)return null;
                amounts.add(n);
            }
        }
        if(amounts.isEmpty()||(!knownResultWindow&&(!explicit||merchant.isEmpty())))return null;
        long amount=amounts.get(0);for(long n:amounts)if(n!=amount)return null;
        // Sanitize before common categorization: no raw page, nickname or other fields.
        JSONObject c=PaymentListener.parse("com.tencent.mm","微信支付","支付成功\n实付金额：¥"+BigDecimal.valueOf(amount,2).toPlainString()+"\n商户："+merchant,"page-result",now);
        if(c==null)return null;
        return c.put("source","accessibility").put("type","expense").put("status","pending").put("successful",true).put("reliable",explicit&&!merchant.isEmpty())
            .put("amount",amount).put("direction","out").put("fundsStatus","paid_out").put("evidence","wechat-paid-merchant-result-v1")
            .put("merchant",merchant).put("reference",reference).put("occurredAt",occurred).put("collectedAt",Instant.ofEpochMilli(now).toString()).put("timeBasis",timeBasis)
            .put("reason","已识别成功付款与实付金额；商户可用时填入用途，可手动编辑。缺少交易时间时按观察时间记录。");
    }
}

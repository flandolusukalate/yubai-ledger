package cn.yubai.ledger;

import org.json.JSONObject;
import java.util.List;
import java.util.regex.Pattern;

/** Join only adjacent identifier lines in the labelled bill column. No amount/ID guessing. */
final class PaymentReference {
    private static final Pattern LABEL=Pattern.compile("^(?:交易单号|交易号|转账单号)\\s*[:：]?\\s*([A-Za-z0-9 ]*)$");
    static String from(List<JSONObject> lines) {
        return from(lines,LABEL);
    }
    static String alipay(List<JSONObject> lines){return from(lines,Pattern.compile("^(?:订单号|交易号)\\s*[:：]?\\s*([A-Za-z0-9 ]*)$"));}
    private static String from(List<JSONObject> lines,Pattern labelPattern) {
        JSONObject label=null,first=null;String value="";boolean inline=false;
        for(JSONObject line:lines){var match=labelPattern.matcher(line.optString("text"));if(!match.matches())continue;
            if(label!=null)throw new IllegalArgumentException("页面有多个交易号，请核对");
            label=line;value=match.group(1).replace(" ","");if(!value.isEmpty()){first=line;inline=true;}}
        if(label==null)return "";
        if(first==null){double closest=Double.MAX_VALUE;
            for(JSONObject line:lines){String token=line.optString("text").replace(" ","");double dy=line.optDouble("y")-label.optDouble("y");
                if(!token.matches("[A-Za-z0-9]{8,64}")||dy<-.015||dy>.06||line.optDouble("x")<label.optDouble("x")-.02)continue;
                if(Math.abs(dy)<closest){closest=Math.abs(dy);first=line;value=token;}}
        }
        if(first==null||!value.matches("[A-Za-z0-9]{8,64}"))return "";
        JSONObject previous=first;
        for(int count=0;count<4;count++){
            JSONObject next=null;double closest=Double.MAX_VALUE;
            for(JSONObject line:lines){String token=line.optString("text").replace(" ","");
                double dy=line.optDouble("y")-previous.optDouble("y"),gap=dy-previous.optDouble("h");
                boolean column=inline&&previous==first?line.optDouble("x")>=first.optDouble("x")+.08&&line.optDouble("x")<first.optDouble("x")+first.optDouble("w"):Math.abs(line.optDouble("x")-previous.optDouble("x"))<.025;
                if(!column||dy<previous.optDouble("h")*.5||gap<-.005||gap>.025||!token.matches("[A-Za-z0-9]{1,64}"))continue;
                boolean newField=false;
                for(JSONObject other:lines)if(other!=line&&Math.abs(other.optDouble("y")-line.optDouble("y"))<.015&&other.optDouble("x")+other.optDouble("w")<line.optDouble("x")+.02&&other.optString("text").matches("[\\p{L}]{2,20}[:：]?")){newField=true;break;}
                if(newField)continue; // e.g. merchant-order ID on the following labelled row is not a continuation.
                if(dy<closest){closest=dy;next=line;}}
            if(next==null)return value;
            value+=next.optString("text").replace(" ","");if(value.length()>64)throw new IllegalArgumentException("交易号过长，请核对");previous=next;
        }
        throw new IllegalArgumentException("交易号换行过多，请核对");
    }
}

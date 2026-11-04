package cn.yubai.ledger;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Local, bounded field discovery. Suggestions are not payment evidence or permission to book. */
final class PaymentFieldLocator {
    private static final String PAID="实付金额|实付全额|实际支付|实付款|实付|付款金额|支付金额";
    private static final String MERCHANT="商户名称|商戶名称|商戶金称|商家名称|收款方|收款商户";
    private static final String NUMBER="(?:人民币)?[-−–]?[¥￥]?[-−–]?(?:[0-9]{1,9}|[1-9][0-9]{0,2}(?:,[0-9]{3})+)(?:\\.[0-9]{1,2})?元?";
    private static final Pattern MONEY=Pattern.compile("^"+NUMBER+"$");
    private static final Pattern PAID_INLINE=Pattern.compile("^(?:"+PAID+")[:：]?"+NUMBER+"$");
    private static final Pattern STATUS=Pattern.compile("^(?:当前状态[:：]?)?(?:支付[成戌]功|付款成功|交易成功)$");
    private static final Pattern OTHER_AMOUNT=Pattern.compile("原价|订单金额|商品金额|优惠|余额|手续费");
    private static final Pattern OUTGOING_SYMBOL=Pattern.compile("[¥￥−–-]");
    private static final Pattern NON_MERCHANT=Pattern.compile("[¥￥]|成功|状态|时间|单号|方式|万式|优惠|余额|实付|原价|订单|完成|服务|喜欢|门店|会员|商品|支付|收款|机构|详情|小程序|返回|取餐|[0-9]{2}:[0-9]{2}");
    private static final Set<String> AMOUNT_KINDS=Set.of("paid-label","outgoing-amount");
    private static final Set<String> MERCHANT_KINDS=Set.of("merchant-label","merchant-above","merchant-below");
    static String compact(String text){return text.replaceAll("\\s+","");}
    static final class Field {
        final String kind,text;final JSONObject rect;final List<JSONObject> parts;
        Field(String kind,String text,List<JSONObject> parts)throws Exception{
            this.kind=kind;this.text=text;this.parts=parts;
            double left=1,top=1,right=0,bottom=0;
            for(JSONObject line:parts){left=Math.min(left,line.getDouble("x"));top=Math.min(top,line.getDouble("y"));right=Math.max(right,line.getDouble("x")+line.getDouble("w"));bottom=Math.max(bottom,line.getDouble("y")+line.getDouble("h"));}
            rect=new JSONObject().put("x",left).put("y",top).put("w",right-left).put("h",bottom-top);
        }
    }
    static List<JSONObject> lines(JSONObject doc)throws Exception{
        JSONArray input=doc.getJSONArray("lines");if(input.length()>200)throw new IllegalArgumentException("页面文字过多");
        List<JSONObject> out=new ArrayList<>();int length=0;
        for(int i=0;i<input.length();i++){
            JSONObject line=input.getJSONObject(i);length+=line.getString("text").length();
            double x=line.getDouble("x"),y=line.getDouble("y"),w=line.getDouble("w"),h=line.getDouble("h");
            if(length>8192||!Double.isFinite(x+y+w+h)||x<0||y<0||w<=0||h<=0||x+w>1.001||y+h>1.001)throw new IllegalArgumentException("页面文字或位置无效");
            out.add(line);
        }
        return out;
    }
    private static double cx(JSONObject r){return r.optDouble("x")+r.optDouble("w")/2;}
    private static double cy(JSONObject r){return r.optDouble("y")+r.optDouble("h")/2;}
    private static boolean within(JSONObject outer,JSONObject inner){return cx(inner)>=outer.optDouble("x")-.001&&cx(inner)<=outer.optDouble("x")+outer.optDouble("w")+.001&&cy(inner)>=outer.optDouble("y")-.001&&cy(inner)<=outer.optDouble("y")+outer.optDouble("h")+.001;}
    private static boolean adjacent(JSONObject label,JSONObject value){
        double rowTolerance=Math.max(label.optDouble("h"),value.optDouble("h"))*.65;
        boolean right=value.optDouble("x")>=label.optDouble("x")+label.optDouble("w")-.015&&Math.abs(cy(label)-cy(value))<=rowTolerance;
        boolean below=value.optDouble("y")>=label.optDouble("y")+label.optDouble("h")-.004&&value.optDouble("y")-label.optDouble("y")-label.optDouble("h")<.045&&Math.abs(cx(value)-cx(label))<.16;
        return right||below;
    }
    private static boolean merchantText(String s){return s.length()>=2&&s.length()<=120&&!NON_MERCHANT.matcher(s).find()&&!s.matches("[-+−–0-9.,:：/年月日 ]+");}
    private static List<Field> statuses(List<JSONObject> lines)throws Exception{
        List<Field> out=new ArrayList<>();for(JSONObject l:lines)if(STATUS.matcher(compact(l.getString("text"))).matches())out.add(new Field("success",l.getString("text"),List.of(l)));return out;
    }
    private static List<Field> labelled(List<JSONObject> lines,boolean amount)throws Exception{
        List<Field> out=new ArrayList<>();String labels=amount?PAID:MERCHANT;
        Pattern labelOnly=Pattern.compile("^[|丨]?(?:"+labels+")[:：]?$");
        Pattern inline=Pattern.compile("^[|丨]?(?:"+labels+")[:：](.+)$");
        for(JSONObject l:lines){
            String text=compact(l.getString("text"));
            if(amount&&PAID_INLINE.matcher(text).matches()){out.add(new Field("paid-label",text,List.of(l)));continue;}
            var match=inline.matcher(text);
            if(!amount&&match.matches()&&merchantText(match.group(1))){out.add(new Field("merchant-label",match.group(1),List.of(l)));continue;}
            if(!labelOnly.matcher(text).matches())continue;
            for(JSONObject value:lines){
                if(value==l||!adjacent(l,value))continue;String v=compact(value.getString("text"));
                if(amount?MONEY.matcher(v).matches():merchantText(v)){
                    Field f=new Field(amount?"paid-label":"merchant-label",amount?text.replaceAll("^[|丨]","")+v:value.getString("text"),List.of(l,value));
                    if(f.rect.optDouble("w")*f.rect.optDouble("h")<=.28)out.add(f);
                }
            }
        }
        return out;
    }
    private static List<Field> amounts(List<JSONObject> lines)throws Exception{
        List<Field> out=labelled(lines,true);if(!out.isEmpty())return out;
        // A balance/original price cannot become the payment merely because its digits are large.
        if(lines.stream().anyMatch(l->OTHER_AMOUNT.matcher(l.optString("text")).find()))return out;
        for(JSONObject l:lines){String s=compact(l.getString("text"));if(MONEY.matcher(s).matches()&&OUTGOING_SYMBOL.matcher(s).find())out.add(new Field("outgoing-amount",s,List.of(l)));}
        return out;
    }
    private static List<Field> nearbyMerchants(List<JSONObject> lines,Field amount,String kind)throws Exception{
        List<Field> out=new ArrayList<>();if(amount==null)return out;
        boolean above=kind.equals("merchant-above");
        for(JSONObject l:lines){double distance=cy(l)-cy(amount.rect);String s=l.getString("text");
            if((above?distance<0:distance>0)&&Math.abs(distance)<.16&&Math.abs(cx(l)-cx(amount.rect))<.20&&merchantText(s))out.add(new Field(kind,s,List.of(l)));
        }
        // Identical repeated merchant text is harmless; different candidates remain ambiguous.
        if(out.size()>1&&out.stream().allMatch(f->compact(f.text).equals(compact(out.get(0).text)))){
            out.sort(java.util.Comparator.comparingDouble(f->Math.abs(cy(f.rect)-cy(amount.rect))));return List.of(out.get(0));
        }
        return out;
    }
    private static Field unique(List<Field> fields){return fields.size()==1?fields.get(0):null;}
    private static List<Field> merchants(List<JSONObject> lines,Field amount)throws Exception{
        List<Field> labelled=labelled(lines,false);if(!labelled.isEmpty())return labelled;
        List<Field> out=new ArrayList<>(nearbyMerchants(lines,amount,"merchant-above"));out.addAll(nearbyMerchants(lines,amount,"merchant-below"));return out;
    }
    static JSONObject suggest(JSONObject doc)throws Exception{
        List<JSONObject> lines=lines(doc);JSONObject fields=new JSONObject(),reasons=new JSONObject();JSONArray missing=new JSONArray();
        Field status=unique(statuses(lines)),amount=unique(amounts(lines)),merchant=unique(merchants(lines,amount));
        for(String key:new String[]{"amount","success","merchant"}){
            Field f=key.equals("amount")?amount:key.equals("success")?status:merchant;
            if(f==null||f.rect.optDouble("w")<.002||f.rect.optDouble("h")<.002||f.rect.optDouble("w")*f.rect.optDouble("h")>.28){missing.put(key);continue;}fields.put(key,f.rect);reasons.put(key,description(f.kind));
        }
        return new JSONObject().put("fields",fields).put("reasons",reasons).put("missing",missing);
    }
    static String description(String kind){return switch(kind){case "paid-label"->"实付标签及其金额";case "outgoing-amount"->"页面中唯一带币种或支出符号的金额";case "success"->"明确的付款成功文字";case "merchant-label"->"商户或收款方标签";case "merchant-above"->"金额上方的商户文字";default->"金额下方的商户文字";};}
    static void validate(JSONObject rule)throws Exception{
        if(rule.has("adaptive")&&!(rule.get("adaptive") instanceof Boolean))throw new IllegalArgumentException("定位模式无效");
        JSONObject locators=rule.optJSONObject("locators");if(rule.has("locators")&&locators==null)throw new IllegalArgumentException("字段定位规则无效");
        if(locators==null){if(rule.optBoolean("enabled")&&rule.optBoolean("adaptive"))throw new IllegalArgumentException("字段尚未学习，请重新测试截图");return;}
        var keys=locators.keys();while(keys.hasNext()){
            String field=keys.next();Object kind=locators.get(field);
            boolean allowed=kind instanceof String&&(field.equals("amount")?AMOUNT_KINDS.contains(kind):field.equals("success")?kind.equals("success"):field.equals("merchant")&&MERCHANT_KINDS.contains(kind));
            if(!allowed||!rule.optBoolean("adaptive")||rule.getJSONObject("fields").optJSONObject(field)==null)throw new IllegalArgumentException("字段定位规则无效");
        }
        if(rule.optBoolean("enabled")&&rule.optBoolean("adaptive"))for(String key:new String[]{"amount","success","merchant"})if(rule.getJSONObject("fields").optJSONObject(key)!=null&&!locators.has(key))throw new IllegalArgumentException("字段尚未学习，请重新测试截图");
    }
    private static Field chosen(List<Field> candidates,JSONObject marked,List<JSONObject> lines)throws Exception{
        if(marked==null)return null;
        List<Field> matches=new ArrayList<>();for(Field f:candidates){
            // Users may mark the value alone; the learned label/value relation restores its role.
            JSONObject value=f.parts.get(f.parts.size()-1);if(!within(marked,value))continue;
            if(lines.stream().anyMatch(l->within(marked,l)&&!f.parts.contains(l)))continue;
            matches.add(f);
        }
        return unique(matches);
    }
    static JSONObject learn(JSONObject rule,JSONObject doc)throws Exception{
        JSONObject copy=new JSONObject(rule.toString());if(!copy.optBoolean("adaptive"))return copy;
        List<JSONObject> lines=lines(doc);JSONObject marked=copy.getJSONObject("fields"),fields=new JSONObject(),locators=new JSONObject();
        Field amount=chosen(amounts(lines),marked.optJSONObject("amount"),lines);
        Field status=chosen(statuses(lines),marked.optJSONObject("success"),lines);
        List<Field> merchantOptions=new ArrayList<>(labelled(lines,false));merchantOptions.addAll(nearbyMerchants(lines,amount,"merchant-above"));merchantOptions.addAll(nearbyMerchants(lines,amount,"merchant-below"));
        // Prefer a semantic merchant label over geometry if both describe the same selected value.
        Field merchant=chosen(labelled(lines,false),marked.optJSONObject("merchant"),lines);
        if(merchant==null)merchant=chosen(merchantOptions,marked.optJSONObject("merchant"),lines);
        for(String key:new String[]{"amount","success","merchant"}){
            if(key.equals("merchant")&&marked.optJSONObject(key)==null)continue;
            Field f=key.equals("amount")?amount:key.equals("success")?status:merchant;
            if(f==null)throw new IllegalArgumentException((key.equals("amount")?"实付金额":key.equals("success")?"成功状态":"商户")+"暂不能明确定位，请调整标记，或切换固定区域模式");
            fields.put(key,f.rect);locators.put(key,f.kind);
        }
        return copy.put("fields",fields).put("locators",locators);
    }
    static JSONObject resolve(JSONObject rule,JSONObject doc)throws Exception{
        List<JSONObject> lines=lines(doc);JSONObject locators=rule.optJSONObject("locators"),out=new JSONObject();
        if(locators==null)throw new IllegalArgumentException("请先学习字段并测试截图");
        List<Field> amountOptions=amounts(lines);String amountKind=locators.optString("amount");amountOptions.removeIf(f->!f.kind.equals(amountKind));Field amount=unique(amountOptions);
        Field status=unique(statuses(lines));Field merchant=null;
        String mk=locators.optString("merchant");if(!mk.isEmpty())merchant=unique(mk.equals("merchant-label")?labelled(lines,false):nearbyMerchants(lines,amount,mk));
        for(String key:new String[]{"amount","success","merchant"}){
            if(!locators.has(key)){if(key.equals("merchant"))continue;throw new IllegalArgumentException("必填字段尚未学习");}
            Field f=key.equals("amount")?amount:key.equals("success")?status:merchant;
            if(f==null)throw new IllegalArgumentException((key.equals("amount")?"实付金额":key.equals("success")?"成功状态":"商户")+"未找到唯一匹配，需要核对");
            out.put(key,f.text);
        }
        return out;
    }
}

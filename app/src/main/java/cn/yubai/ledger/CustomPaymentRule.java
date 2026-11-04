package cn.yubai.ledger;

import org.json.JSONArray;
import org.json.JSONObject;
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

/** A bounded declarative receipt template, not executable user code. All money is integer fen. */
final class CustomPaymentRule {
    static final String EVIDENCE="wechat-custom-paid-page-v1";
    static final String PKG="com.tencent.mm";
    static final int MAX_RULES=12;
    static String packageFor(String origin){return "alipay".equals(origin)?AlipayCaptureWindow.PKG:PKG;}
    static boolean windowAllowed(String value){return windowAllowed(value,"wechat");}
    static boolean windowAllowed(String value,String origin){
        return value!=null&&("wechat".equals(origin)?value.startsWith(PKG+"."):"alipay".equals(origin)&&value.startsWith("com.alipay."))&&value.length()<240&&value.matches("[A-Za-z0-9_.$]+")
            &&!Pattern.compile("(?i)LauncherUI|LauncherActivity|chatting|password|passwd|pwd|fingerprint|WalletPayUI|WalletCheckPwd|login|settings|contact|gallery|ImagePreview|ImageGallery").matcher(value).find();
    }
    static void validate(JSONObject rule) throws Exception {
        if(rule.getInt("version")!=1||!rule.getString("id").matches("[A-Za-z0-9_-]{1,80}")||rule.getString("name").trim().isEmpty()||rule.getString("name").length()>50
            ||!java.util.Set.of("wechat","alipay").contains(rule.getString("origin"))||rule.getInt("revision")<1||rule.getInt("revision")>1_000_000)throw new IllegalArgumentException("规则格式或版本无效");
        if(!(rule.get("enabled") instanceof Boolean))throw new IllegalArgumentException("规则开关无效");
        double aspect=rule.getDouble("aspect");if(!Double.isFinite(aspect)||aspect<0.25||aspect>2.5)throw new IllegalArgumentException("截图比例无效");
        java.util.Set<String> allowed=java.util.Set.of("id","version","revision","name","origin","enabled","aspect","fields","testedHash","windowClass","matchText","adaptive","locators");
        var keys=rule.keys();while(keys.hasNext())if(!allowed.contains(keys.next()))throw new IllegalArgumentException("规则包含不支持的字段");
        JSONObject fields=rule.getJSONObject("fields");
        var fieldKeys=fields.keys();while(fieldKeys.hasNext())if(!java.util.Set.of("amount","merchant","success").contains(fieldKeys.next()))throw new IllegalArgumentException("规则字段无效");
        for(String key:new String[]{"amount","success","merchant"}){
            if(!fields.has(key)||fields.isNull(key)){if(key.equals("merchant")||!rule.getBoolean("enabled"))continue;throw new IllegalArgumentException("请标记实付金额和成功状态");}
            JSONObject rect=fields.getJSONObject(key);double x=rect.getDouble("x"),y=rect.getDouble("y"),w=rect.getDouble("w"),h=rect.getDouble("h");
            var rectKeys=rect.keys();while(rectKeys.hasNext())if(!java.util.Set.of("x","y","w","h").contains(rectKeys.next()))throw new IllegalArgumentException("标记区域包含不支持的字段");
            if(!Double.isFinite(x+y+w+h)||x<0||y<0||w<.002||h<.002||x+w>1.001||y+h>1.001||w*h>.28)throw new IllegalArgumentException("标记区域无效或过大");
        }
        String window=rule.optString("windowClass");if(!window.isEmpty()&&!windowAllowed(window,rule.getString("origin")))throw new IllegalArgumentException("此窗口不允许用于付款规则");
        if(!rule.optString("testedHash").isEmpty()&&!rule.optString("testedHash").matches("[a-f0-9]{64}"))throw new IllegalArgumentException("规则测试标记无效");
        if(rule.has("matchText")&&(!(rule.get("matchText") instanceof String)||rule.getString("matchText").length()>40||Pattern.compile("[\\p{Cc}\\p{Cf}]").matcher(rule.getString("matchText")).find()))throw new IllegalArgumentException("页面识别词最多40字");
        if(rule.toString().length()>5000)throw new IllegalArgumentException("规则内容过大");
        PaymentFieldLocator.validate(rule);
    }
    static String signature(JSONObject rule) throws Exception {
        validate(rule);StringBuilder text=new StringBuilder("rule-v1|").append(rule.getString("id")).append('|').append(rule.getInt("revision")).append('|').append(rule.getString("name")).append('|').append(rule.getString("origin")).append('|').append(rule.getDouble("aspect"));
        for(String key:new String[]{"amount","success","merchant"}){
            text.append('|').append(key);JSONObject r=rule.getJSONObject("fields").optJSONObject(key);
            if(r!=null)for(String p:new String[]{"x","y","w","h"})text.append('|').append(r.getDouble(p));
        }
        if(!rule.optString("matchText").isEmpty())text.append("|matchText|").append(rule.getString("matchText"));
        if(rule.optBoolean("adaptive")){
            text.append("|adaptive-v1");JSONObject locators=rule.optJSONObject("locators");
            for(String key:new String[]{"amount","success","merchant"})text.append('|').append(key).append('|').append(locators==null?"":locators.optString(key));
        }
        return PaymentListener.digest(text.toString());
    }
    private static List<JSONObject> ordered(JSONObject doc) throws Exception {
        JSONArray input=doc.getJSONArray("lines");if(input.length()>200)throw new IllegalArgumentException("页面文字过多");
        List<JSONObject> lines=new ArrayList<>();for(int i=0;i<input.length();i++)lines.add(input.getJSONObject(i));
        lines.sort(Comparator.comparingDouble((JSONObject o)->o.optDouble("y")).thenComparingDouble(o->o.optDouble("x")));return lines;
    }
    private static String selected(List<JSONObject> lines,JSONObject rect){
        if(rect==null)return "";StringBuilder out=new StringBuilder();
        // Prefer the actual marked region. Tolerance must not pull an adjacent
        // bill row into a region that already contains its intended OCR line.
        double rx=rect.optDouble("x"),ry=rect.optDouble("y"),rw=rect.optDouble("w"),rh=rect.optDouble("h");
        for(JSONObject line:lines){double cx=line.optDouble("x")+line.optDouble("w")/2,cy=line.optDouble("y")+line.optDouble("h")/2;
            if(cx>=rx-.001&&cx<=rx+rw+.001&&cy>=ry-.001&&cy<=ry+rh+.001){if(out.length()>0)out.append('\n');out.append(line.optString("text"));}}
        if(out.length()>0)return out.toString().trim();
        // Small tolerance for window-vs-full-screenshot system bars; one distinct amount is still required.
        double x=rect.optDouble("x")-.018,y=rect.optDouble("y")-.025,right=x+rect.optDouble("w")+.036,bottom=y+rect.optDouble("h")+.05;
        for(JSONObject line:lines){double cx=line.optDouble("x")+line.optDouble("w")/2,cy=line.optDouble("y")+line.optDouble("h")/2;
            if(cx>=x&&cx<=right&&cy>=y&&cy<=bottom){if(out.length()>0)out.append('\n');out.append(line.optString("text"));}}
        return out.toString().trim();
    }
    static void checkContext(JSONObject rule,String text)throws Exception {
        if(text.length()>8192||Pattern.compile("失败|未成功|不成功|取消|已关闭|处理中|待支付|退款|退回|验证码|密码|口令|红包|充值|提现|还款|借款|代付|报销|收款成功|收款到账|收入金额|已收款|已收钱|待.{0,160}(?:收款|领取)|发送|按住说话|通讯录|朋友圈").matcher(text).find())throw new IllegalArgumentException("页面含未完成、收入、退款或敏感内容，不自动记付款");
        String matchText=rule.optString("matchText").replaceAll("\\s+","");
        if(!matchText.isEmpty()&&!text.replaceAll("\\s+","").contains(matchText))throw new IllegalArgumentException("页面识别词未匹配，不是这类付款页");
    }
    static JSONObject pageTime(String text,long now,ZoneId zone,boolean bill)throws Exception {
        String stamp=PaymentListener.match(text,"(?:交易时间|支付时间|转账时间)\\s*[:：]?\\s*(20[0-9]{2}(?:年|[-/])[0-9]{1,2}(?:月|[-/])[0-9]{1,2}日?\\s+[0-9]{2}:[0-9]{2}(?::[0-9]{2})?)");
        String occurred=Instant.ofEpochMilli(now).toString(),basis="page-observed-time";
        if(!stamp.isEmpty()){
            String normalized=stamp.replace('年','-').replace('月','-').replace("日","").replace('/','-').replaceAll("\\s+"," ").trim();if(normalized.length()-normalized.lastIndexOf(' ')==6)normalized+=":00";
            occurred=LocalDateTime.parse(normalized,DateTimeFormatter.ofPattern("uuuu-M-d HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)).atZone(zone).toInstant().toString();
            if(Instant.parse(occurred).toEpochMilli()>now+300_000)throw new IllegalArgumentException("交易时间晚于当前时间，请核对");basis="page-local-time-book-zone";
        }else if(bill)throw new IllegalArgumentException("账单详情的实际交易时间未读到，不以今天替代历史日期");
        return new JSONObject().put("occurredAt",occurred).put("timeBasis",basis);
    }
    static boolean isBill(String text,boolean alipay){
        return (text.contains("当前状态")||alipay&&text.contains("账单详情"))&&Pattern.compile("支付[方万]式|付款方式|全部账单|账单服务|扫二维码付款|扫码付款").matcher(text).find()&&Pattern.compile("交易时间|支付时间|转账时间").matcher(text).find()&&Pattern.compile(alipay?"订单号|交易号":"交易单号|交易号|转账单号").matcher(text).find();
    }
    static JSONObject reviewCandidate(JSONObject rule,JSONObject doc,long now,String session,ZoneId zone)throws Exception{
        validate(rule);if(!rule.optBoolean("adaptive"))return null;
        List<JSONObject> lines=ordered(doc);String text=String.join("\n",lines.stream().map(l->l.optString("text")).collect(java.util.stream.Collectors.toList()));
        checkContext(rule,text);
        try{PaymentFieldLocator.resolve(rule,doc);return null;}catch(IllegalArgumentException ambiguous){/* only uncertain field location is eligible */}
        if(!PaymentFieldLocator.suggest(doc).getJSONObject("fields").has("success"))return null;
        boolean alipay=rule.getString("origin").equals("alipay");
        boolean bill=isBill(text,alipay);
        boolean result=Pattern.compile("实付金额|实付全额|实际支付|实付款|付款金额|支付金额").matcher(text).find()&&(lines.stream().anyMatch(l->l.optString("text").trim().equals("完成"))||rule.optString("matchText").length()>=2&&Pattern.compile("查看订单|返回商家|订单详情|取餐号").matcher(text).find());
        if(!bill&&!result)return null;
        JSONObject time=pageTime(text,now,zone,bill);
        String reference=alipay?(bill?PaymentReference.alipay(lines):""):PaymentReference.from(lines);
        if(alipay&&!reference.isEmpty()&&!reference.matches("20[0-9]{26}"))reference="";
        String origin=rule.getString("origin");if(!reference.isEmpty())reference=origin+":"+reference;
        // Only a hash of payment-related anchors is retained. The OCR document stays in memory.
        String anchors=String.join("|",lines.stream().map(l->l.optString("text")).filter(t->Pattern.compile("实付|付款金额|支付金额|成功").matcher(t).find()).sorted().collect(java.util.stream.Collectors.toList()));
        String identity=reference.isEmpty()?rule.getString("id")+"|"+session+"|"+anchors+(bill?"|"+time.getString("occurredAt"):""):reference;
        return new JSONObject().put("id",java.util.UUID.randomUUID().toString()).put("source","accessibility").put("captureMethod","accessibility-window-ocr").put("origin",origin)
            .put("type","expense").put("status","pending").put("amount",JSONObject.NULL).put("merchant","").put("reference",reference).put("successful",false).put("reliable",false)
            .put("occurredAt",time.getString("occurredAt")).put("timeBasis",time.getString("timeBasis")).put("collectedAt",Instant.ofEpochMilli(now).toString())
            .put("evidence",origin+"-custom-review-page-v1").put("fingerprint",PaymentListener.digest("adaptive-review|"+origin+"|"+identity))
            .put("reason","识别到付款页面，但字段未找到唯一匹配；尚未扣款，请核对金额、用途与实际日期");
    }
    static JSONObject evaluate(JSONObject rule,JSONObject doc,long now,String session,ZoneId zone) throws Exception {
        validate(rule);double aspect=(double)doc.getInt("width")/doc.getInt("height");
        if(!Double.isFinite(aspect)||aspect<.25||aspect>2.5||!rule.optBoolean("adaptive")&&Math.abs(aspect/rule.getDouble("aspect")-1)>.10)throw new IllegalArgumentException("页面比例已变化，请重新添加样本并测试");
        List<JSONObject> lines=ordered(doc);String text=String.join("\n",lines.stream().map(l->l.optString("text")).collect(java.util.stream.Collectors.toList()));
        checkContext(rule,text);
        String matchText=rule.optString("matchText").replaceAll("\\s+","");
        String origin=rule.getString("origin");boolean alipay=origin.equals("alipay");
        JSONObject fields=rule.getJSONObject("fields");
        if(fields.optJSONObject("amount")==null||fields.optJSONObject("success")==null)throw new IllegalArgumentException("请先标记实付金额和成功状态");
        JSONObject located=rule.optBoolean("adaptive")?PaymentFieldLocator.resolve(rule,doc):null;
        String status=(located==null?selected(lines,fields.getJSONObject("success")):located.getString("success")).replace("支付戌功","支付成功").replaceAll("\\s+","");
        if(!status.matches("(?:当前状态[:：]?)?(?:支付成功|付款成功|交易成功)"))throw new IllegalArgumentException("标记区域未读到明确成功状态");
        String amountText=(located==null?selected(lines,fields.getJSONObject("amount")):located.getString("amount")).replaceAll("\\s+","").replace('−','-').replace('–','-');
        if(amountText.contains("+")||!amountText.matches("(?:实付金额|实付全额|实际支付|实付款|实付|付款金额|支付金额)?[:：]?(?:人民币)?-?[¥￥*]?-?(?:[0-9]+|[1-9][0-9]{0,2}(?:,[0-9]{3})+)(?:\\.[0-9]{1,2})?元?"))throw new IllegalArgumentException("金额区域不是唯一实付金额，请缩小标记，避开余额和优惠");
        if(Pattern.compile("原价|订单金额|商品金额|优惠|余额|手续费").matcher(text).find()&&!Pattern.compile("^(?:实付金额|实付全额|实际支付|实付款|实付|付款金额|支付金额)").matcher(amountText).find())throw new IllegalArgumentException("页面含其他金额，请把实付标签和实付金额一起标记");
        String numeric=amountText.replaceAll("实付金额|实付全额|实际支付|实付款|实付|付款金额|支付金额|人民币|[:：¥￥*元,-]","");
        long amount=new BigDecimal(numeric).movePointRight(2).longValueExact();if(amount<=0||amount>1_000_000_000_000L)throw new IllegalArgumentException("实付金额必须大于零");
        // Status + an arbitrary balance are insufficient: require a complete result or bill layout.
        boolean result=lines.stream().anyMatch(l->l.optString("text").trim().equals("完成"));
        // ML Kit can read the method label as 支付万式. Keep all other bill guards;
        // this label compatibility never changes an amount or transaction identifier.
        boolean bill=isBill(text,alipay);
        boolean miniResult=matchText.length()>=2&&status.matches("支付成功|付款成功")&&Pattern.compile("^(?:实付金额|实付全额|实际支付|实付款|实付|付款金额|支付金额)").matcher(amountText).find()&&Pattern.compile("查看订单|返回商家|订单详情|取餐号").matcher(text).find();
        if(status.endsWith("交易成功")&&!bill&&!Pattern.compile("实付|付款金额|支付金额|支出|扫码付款|扫二维码付款").matcher(text).find())throw new IllegalArgumentException("交易成功未明确付款方向，请选择付款方页面");
        if(!result&&!bill&&!miniResult)throw new IllegalArgumentException("缺少付款结果或账单详情结构，不能仅凭金额和成功字样启用");
        if(bill&&!amountText.contains("-")&&!Pattern.compile("实付|付款金额|支付金额|支出").matcher(amountText+text).find())throw new IllegalArgumentException("账单详情没有明确向外付款方向");
        String merchant=(located==null?selected(lines,fields.optJSONObject("merchant")):located.optString("merchant")).replace('\n',' ').trim();
        if(merchant.length()>120||Pattern.compile("[¥￥]|支付成功|付款成功|交易成功|当前状态|支付方式|转账单号|交易单号|完成|待.{0,80}收款").matcher(merchant).find()||merchant.matches("[-+0-9., ]+"))throw new IllegalArgumentException("商户区域包含其他字段，请重新标记或选择不提取商户");
        String reference=alipay?(bill?PaymentReference.alipay(lines):""):PaymentReference.from(lines);
        if(alipay&&!reference.isEmpty()&&!reference.matches("20[0-9]{26}"))reference="";
        JSONObject time=pageTime(text,now,zone,bill);String occurred=time.getString("occurredAt"),basis=time.getString("timeBasis");
        String key=PaymentListener.digest(alipay?"alipay-paid-page-v1|"+amount+"|"+merchant:"wechat-paid-merchant-result-v1|"+amount+"||"+merchant);
        return new JSONObject().put("id",java.util.UUID.randomUUID().toString()).put("origin",origin).put("source","accessibility").put("captureMethod","accessibility-window-ocr")
            .put("evidence",origin+"-custom-paid-page-v1").put("amount",amount).put("merchant",merchant).put("type","expense").put("status","pending").put("successful",true).put("reliable",false)
            .put("direction","out").put("fundsStatus","paid_out").put("reference",reference.isEmpty()?"":origin+":"+reference).put("referenceLayout",reference.isEmpty()?"":"bounded-lines-v1").put("occurredAt",occurred).put("collectedAt",Instant.ofEpochMilli(now).toString()).put("timeBasis",basis)
            .put("paymentEvidenceKey",key).put("fingerprint",PaymentListener.digest("custom-page|"+session+"|"+key+"|"+reference+"|"+(basis.equals("page-observed-time")?"":occurred)))
            .put("customRuleId",rule.getString("id")).put("customRuleRevision",rule.getInt("revision")).put("customRuleHash",signature(rule)).put("customWindow",rule.optString("windowClass"))
            .put("reason","自定义付款规则已匹配，正在统一查重；用途可稍后修改");
    }
}

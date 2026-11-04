package cn.yubai.ledger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local test attestations are device permissions, never imported from a backup. */
final class CustomRuleRegistry {
    private static volatile Binding pending;
    static final class Binding {
        final JSONObject rule;final long deadline;
        Binding(JSONObject r){rule=r;deadline=SystemClock.elapsedRealtime()+60_000;}
    }
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("custom_rule_proofs",0);}
    static java.time.ZoneId zone(Context c){return java.time.ZoneId.of(c.getSharedPreferences("collector",0).getString("ledger_timezone","Asia/Shanghai"));}
    static JSONArray rules(String ledger) throws Exception {
        if(ledger.trim().isEmpty())return new JSONArray();
        JSONObject settings=new JSONObject(ledger).getJSONObject("settings");
        if(!settings.has("customRules"))return new JSONArray();
        if(!(settings.get("customRules") instanceof JSONArray))throw new IllegalArgumentException("自定义规则必须是列表");
        return settings.getJSONArray("customRules");
    }
    static void validateList(JSONArray rules) throws Exception {
        if(rules.length()>CustomPaymentRule.MAX_RULES)throw new IllegalArgumentException("最多保存12条付款规则");
        java.util.HashSet<String> ids=new java.util.HashSet<>();
        for(int i=0;i<rules.length();i++){JSONObject r=rules.getJSONObject(i);CustomPaymentRule.validate(r);if(!ids.add(r.getString("id")))throw new IllegalArgumentException("重复规则标识");}
    }
    static boolean tested(Context c,JSONObject rule) throws Exception {String sig=CustomPaymentRule.signature(rule);return sig.equals(rule.optString("testedHash"))&&sig.equals(prefs(c).getString("tested:"+rule.getString("id"),""));}
    static boolean bound(Context c,JSONObject rule) throws Exception {return tested(c,rule)&&CustomPaymentRule.windowAllowed(rule.optString("windowClass"),rule.getString("origin"))&&(CustomPaymentRule.signature(rule)+"|"+rule.optString("windowClass")).equals(prefs(c).getString("bound:"+rule.getString("id"),""));}
    static String recordTest(Context c,JSONObject rule) throws Exception {
        String sig=CustomPaymentRule.signature(rule),id=rule.getString("id");
        prefs(c).edit().putString("tested:"+id,sig).remove("bound:"+id).apply();return sig;
    }
    static void validateCommit(Context c,String ledger) throws Exception {
        JSONArray rules=rules(ledger);validateList(rules);
        for(int i=0;i<rules.length();i++){JSONObject rule=rules.getJSONObject(i);if(rule.getBoolean("enabled")&&!bound(c,rule))throw new IllegalArgumentException("请先测试截图并绑定真实付款窗口，再启用规则");}
    }
    static JSONArray active(Context c,String ledger) throws Exception {
        JSONArray all=rules(ledger),active=new JSONArray();validateList(all);
        for(int i=0;i<all.length();i++){JSONObject r=all.getJSONObject(i);if(r.getBoolean("enabled")&&bound(c,r))active.put(r);}return active;
    }
    static synchronized JSONObject begin(Context c,JSONObject rule) throws Exception {
        if(!tested(c,rule))throw new IllegalArgumentException("截图尚未测试通过，或修改后需要重新测试");
        pending=new Binding(new JSONObject(rule.toString()));
        JSONObject status=new JSONObject().put("state","waiting").put("id",rule.getString("id")).put("message","60秒内打开对应来源的真实付款页面；本次仅测试，不生成账单");
        prefs(c).edit().putString("bindingStatus",status.toString()).apply();return status;
    }
    static Binding binding(){Binding b=pending;if(b!=null&&SystemClock.elapsedRealtime()<b.deadline)return b;pending=null;return null;}
    static synchronized void cancel(Context c){pending=null;prefs(c).edit().remove("bindingStatus").apply();}
    static synchronized JSONObject finishBinding(Context c,Binding b,String window,JSONObject doc) throws Exception {
        if(b!=binding())return null;
        JSONObject rule=new JSONObject(b.rule.toString()).put("windowClass",window);
        JSONObject candidate=CustomPaymentRule.evaluate(rule,doc,System.currentTimeMillis(),"binding-preview",zone(c));
        pending=null;
        prefs(c).edit().putString("bound:"+rule.getString("id"),CustomPaymentRule.signature(rule)+"|"+window)
            .putString("bindingStatus",new JSONObject().put("state","passed").put("id",rule.getString("id")).put("windowClass",window).put("candidate",candidate)
                .put("message","真实窗口识别通过；回到规则页核对后启用。本次没有记账。").toString()).apply();
        return candidate;
    }
    static void bindingError(Context c,String message){try{Binding b=binding();if(b!=null)prefs(c).edit().putString("bindingStatus",new JSONObject().put("state","waiting").put("id",b.rule.optString("id")).put("message",message).toString()).apply();}catch(Exception ignored){}}
    static JSONObject status(Context c,String ledger) throws Exception {
        JSONObject report=new JSONObject(),all=new JSONObject();JSONArray rules=rules(ledger);
        for(int i=0;i<rules.length();i++){JSONObject r=rules.getJSONObject(i);all.put(r.getString("id"),new JSONObject().put("tested",tested(c,r)).put("bound",bound(c,r)));}
        JSONObject b=new JSONObject(prefs(c).getString("bindingStatus","{}"));
        if(b.optString("state").equals("waiting")&&binding()==null)b.put("state","expired").put("message","窗口测试已结束，请重新开始；未生成账单");
        return report.put("rules",all).put("binding",b);
    }
    static String forRestore(String body) throws Exception {
        JSONObject ledger=new JSONObject(body);JSONArray rules=rules(body);validateList(rules);
        for(int i=0;i<rules.length();i++)rules.getJSONObject(i).put("enabled",false).put("testedHash","").put("windowClass","");
        if(rules.length()>0)ledger.getJSONObject("settings").put("customRules",rules);return ledger.toString();
    }
    static void resetProofs(Context c){pending=null;prefs(c).edit().clear().apply();}
}

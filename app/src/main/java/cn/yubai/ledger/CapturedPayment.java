package cn.yubai.ledger;

import android.content.Context;
import android.os.SystemClock;
import java.time.ZoneId;
import org.json.JSONArray;
import org.json.JSONObject;

/** Frozen identity/permissions for one accepted window image. Contains no pixels or OCR text. */
final class CapturedPayment {
    final long epoch,observedAt,lifecycle,revision;
    final int window;
    final String session,origin,packageName,windowClass;
    final ZoneId zone;
    final boolean result,custom,builtIn;
    final CustomRuleRegistry.Binding binding;
    private final String rules;
    private final long deadline=SystemClock.elapsedRealtime()+120_000;
    private volatile boolean delivered;

    CapturedPayment(Context context,long epoch,int window,String session,String packageName,String windowClass,
        boolean result,boolean custom,CustomRuleRegistry.Binding binding,long lifecycle,long observedAt)throws Exception {
        this.epoch=epoch;this.window=window;this.session=session;this.packageName=packageName;this.windowClass=windowClass;
        if(!packageName.equals("com.tencent.mm")&&!packageName.equals(AlipayCaptureWindow.PKG))throw new IllegalArgumentException("Unsupported source");
        origin=packageName.equals(AlipayCaptureWindow.PKG)?"alipay":"wechat";
        this.result=result;this.custom=custom;this.binding=binding;this.lifecycle=lifecycle;this.observedAt=observedAt;
        var prefs=context.getSharedPreferences("collector",0);
        revision=prefs.getLong("page_capture_revision",0);zone=CustomRuleRegistry.zone(context);
        rules=new JSONArray(prefs.getString("custom_rules_active","[]")).toString();
        builtIn=binding==null&&(origin.equals("alipay")?AlipayCaptureWindow.allowed(windowClass):WechatCaptureWindow.allowed(windowClass));
    }
    JSONArray rules()throws Exception{return new JSONArray(rules);}
    boolean active(Context context,long currentLifecycle,boolean running){
        return !delivered&&authorized(context,currentLifecycle,running);
    }
    boolean authorized(Context context,long currentLifecycle,boolean running){
        var prefs=context.getSharedPreferences("collector",0);
        return running&&lifecycle==currentLifecycle&&SystemClock.elapsedRealtime()<deadline
            &&revision==prefs.getLong("page_capture_revision",0)&&prefs.getBoolean("page_capture_enabled",false)
            &&prefs.getBoolean("page_ocr_enabled",false)&&prefs.getBoolean(origin.equals("alipay")?"page_capture_alipay":"page_capture_wechat",false);
    }
    interface Delivery {void run()throws Exception;}
    synchronized boolean deliver(Delivery action)throws Exception{
        if(delivered)return false;
        action.run();delivered=true;return true;
    }
    JSONObject parseBuiltIn(JSONObject document,String text)throws Exception {
        JSONObject c=origin.equals("alipay")?AlipayPaymentPage.parse(document,observedAt,session,windowClass,zone):ScreenOcrParser.parse(text,observedAt,session,result);
        if(c==null)return null;
        c.put("source","accessibility").put("captureMethod","accessibility-window-ocr");
        if(origin.equals("wechat")){c.put("paymentEvidenceKey",c.getString("screenEvidenceKey"));c.remove("screenEvidenceKey");}
        c.put("fingerprint",PaymentListener.digest("accessibility-window-ocr|"+session+"|"+c.getString("paymentEvidenceKey")+"|"+c.getString("occurredAt")));
        return c;
    }
}

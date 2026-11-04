package cn.yubai.ledger;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Opt-in page reader. Never clicks or persists page text. Window OCR has separate consent. */
public final class PageCaptureService extends AccessibilityService {
    public static volatile boolean connected=false;
    private Store store;
    private HandlerThread thread;
    private Handler handler;
    private volatile String session=UUID.randomUUID().toString();
    private final Object eventLock=new Object();
    private final ArrayDeque<AccessibilityEvent> pendingEvents=new ArrayDeque<>();
    private boolean scheduled=false;
    private volatile long generation=0;
    private volatile int paymentWindow=-1;
    private volatile boolean resultWindow=false;
    private volatile String currentWindowClass="";
    private volatile String currentPackage="";
    private volatile boolean customWindow=false;
    private boolean sensitiveNodes=false;
    private volatile long previewGeneration=-1;
    private volatile CustomRuleRegistry.Binding previewBinding;
    private volatile boolean destroyed=false;
    private volatile long captureLifecycle;
    private PageWindowOcr windowOcr;
    private String lastState="",lastError="";
    private String lastQueuedFingerprint="";
    private volatile long capturedGeneration=-1;
    private int unreadableRetries=0;
    // Window transitions can expose an empty root before content arrives. Retry
    // at most twice, then wait for another actual WeChat event; never poll forever.
    private final Runnable readTask=new Runnable(){@Override public void run(){
        List<AccessibilityEvent> events;long epoch;String visit;
        synchronized(eventLock){events=new ArrayList<>(pendingEvents);pendingEvents.clear();epoch=generation;visit=session;}
        boolean retry=false;
        try{retry=readPage(events,epoch,visit);}finally{
            for(AccessibilityEvent event:events)event.recycle();
            synchronized(eventLock){
                if(!pendingEvents.isEmpty()){unreadableRetries=0;handler.postDelayed(this,250);}
                else if(retry&&unreadableRetries++<2&&connected&&enabled())handler.postDelayed(this,900);
                else{unreadableRetries=0;scheduled=false;}
            }
        }
    }};
    static synchronized void syncSettings(Context context,String json) throws Exception {
        JSONObject settings=new JSONObject(json).optJSONObject("settings");
        boolean selected=false,alipay=false;JSONArray sources=settings==null?null:settings.optJSONArray("sources");
        if(sources!=null)for(int i=0;i<sources.length();i++){if("com.tencent.mm".equals(sources.optString(i)))selected=true;if(AlipayCaptureWindow.PKG.equals(sources.optString(i)))alipay=true;}
        var prefs=context.getSharedPreferences("collector",0);
        boolean capture=settings!=null&&settings.optBoolean("pageCapture",false),ocr=settings!=null&&settings.optBoolean("pageOcr",false);
        boolean selectedAlipay=alipay&&settings!=null&&settings.optBoolean("alipayPageCapture",false);
        String rules=CustomRuleRegistry.active(context,json).toString();
        String control=capture+"|"+selected+"|"+selectedAlipay+"|"+ocr+"|"+rules;
        long revision=prefs.getLong("page_capture_revision",0)+(control.equals(prefs.getString("page_capture_control",""))?0:1);
        prefs.edit().putBoolean("page_capture_enabled",capture).putBoolean("page_capture_wechat",selected).putBoolean("page_capture_alipay",selectedAlipay)
            .putBoolean("page_ocr_enabled",ocr).putString("custom_rules_active",rules).putLong("page_capture_revision",revision).putString("page_capture_control",control)
            .putString("ledger_timezone",settings==null?"Asia/Shanghai":settings.optString("timezone","Asia/Shanghai")).apply();
    }
    private boolean enabled(){var p=getSharedPreferences("collector",0);return p.getBoolean("page_capture_enabled",false)&&(p.getBoolean("page_capture_wechat",false)||p.getBoolean("page_capture_alipay",false));}
    private boolean enabledPackage(String pkg){var p=getSharedPreferences("collector",0);return p.getBoolean("page_capture_enabled",false)&&("com.tencent.mm".equals(pkg)?p.getBoolean("page_capture_wechat",false):AlipayCaptureWindow.PKG.equals(pkg)&&p.getBoolean("page_capture_alipay",false));}
    private synchronized void status(String value,String error){if(value.equals(lastState)&&error.equals(lastError))return;lastState=value;lastError=error;getSharedPreferences("collector",0).edit().putString("page_state",value).putString("page_error",error).apply();}
    @Override public void onCreate(){super.onCreate();store=new Store(this);thread=new HandlerThread("yubai-page-reader");thread.start();handler=new Handler(thread.getLooper());windowOcr=new PageWindowOcr(this,handler);}
    @Override protected void onServiceConnected(){connected=true;handler.post(()->{try{String saved=store.load();if(!saved.isEmpty())syncSettings(this,saved);status("idle","");}catch(Exception e){status("error","页面识别设置读取失败");}});}
    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        if(event==null)return;String pkg=event.getPackageName()==null?"":event.getPackageName().toString();if(!enabledPackage(pkg))return;
        CustomRuleRegistry.Binding testing=CustomRuleRegistry.binding();
        if(testing!=null&&!CustomPaymentRule.packageFor(testing.rule.optString("origin")).equals(pkg))return;
        int type=event.getEventType();if(type!=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED&&type!=AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)return;
        if(type==AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED&&!pkg.equals(currentPackage))return;
        synchronized(eventLock){
            if(destroyed)return;
            if(type==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED){
                generation++;session=UUID.randomUUID().toString();
                currentPackage=pkg;previewBinding=CustomRuleRegistry.binding();if(previewBinding!=null&&!CustomPaymentRule.packageFor(previewBinding.rule.optString("origin")).equals(pkg))previewBinding=null;previewGeneration=previewBinding==null?-1:generation;
                String name=event.getClassName()==null?"":event.getClassName().toString();
                currentWindowClass=name;
                customWindow=hasCustomRule(name)||(previewBinding!=null&&CustomPaymentRule.windowAllowed(name,previewBinding.rule.optString("origin")));
                paymentWindow=((isAlipay()?AlipayCaptureWindow.allowed(name):WechatCaptureWindow.allowed(name))||customWindow)?event.getWindowId():-1;
                getSharedPreferences("collector",0).edit().putString("page_source_package",pkg).putString("page_window_class",name).putString("page_ocr_state",paymentWindow<0?"unadapted":"idle")
                    .putString("page_ocr_message",paymentWindow<0?(isAlipay()?"当前支付宝窗口尚未适配，未自动扣款；可手动记账或导入账单":"当前窗口尚未适配；可在自定义适配中添加付款页规则"):"等待当前付款窗口识别").apply();
                resultWindow=isAlipay()?AlipayCaptureWindow.result(name):WechatCaptureWindow.result(name);
                while(!pendingEvents.isEmpty())pendingEvents.removeFirst().recycle();
            }
            // Copy before returning to Android; only two event types are retained.
            // Binder node reads and traversal stay off the main thread.
            var iterator=pendingEvents.iterator();
            while(iterator.hasNext()){AccessibilityEvent old=iterator.next();if(old.getEventType()==type){iterator.remove();old.recycle();}}
            pendingEvents.addLast(AccessibilityEvent.obtain(event));
            if(!scheduled){scheduled=true;handler.postDelayed(readTask,250);}
        }
    }
    @Override public void onInterrupt(){synchronized(eventLock){captureLifecycle++;generation++;paymentWindow=-1;handler.removeCallbacks(readTask);while(!pendingEvents.isEmpty())pendingEvents.removeFirst().recycle();scheduled=false;}status("idle","");}
    private static final class Node {final AccessibilityNodeInfo value;final int depth;Node(AccessibilityNodeInfo v,int d){value=v;depth=d;}}
    @SuppressWarnings("deprecation") private List<String> readNodes(AccessibilityNodeInfo root,String route){
        ArrayDeque<Node> nodes=new ArrayDeque<>();
        try{
            if(root==null)return List.of();
            if(!currentPackage.contentEquals(root.getPackageName()==null?"":root.getPackageName())){root.recycle();return List.of();}
            nodes.add(new Node(root,0));List<String> lines=new ArrayList<>();int count=0,chars=0;long started=SystemClock.uptimeMillis();
            while(!nodes.isEmpty()){
                if(count++>=200||SystemClock.uptimeMillis()-started>120){sensitiveNodes=true;return List.of();}
                Node item=nodes.removeFirst();AccessibilityNodeInfo n=item.value;
                try{
                    if(n.isPassword()||n.isEditable()){sensitiveNodes=true;return List.of();}
                    CharSequence readable=n.getText();if(readable==null||readable.toString().trim().isEmpty())readable=n.getContentDescription();
                    if(n.isVisibleToUser()&&readable!=null){String text=readable.toString().trim();if(!text.isEmpty()){chars+=text.length();if(chars>8192){sensitiveNodes=true;return List.of();}for(String line:text.split("\\r?\\n"))if(!line.trim().isEmpty())lines.add(line.trim());}}
                    int children=n.getChildCount();if((item.depth>=24&&children>0)||nodes.size()+children+count>200){sensitiveNodes=true;return List.of();}
                    for(int i=0;i<children;i++){AccessibilityNodeInfo child=n.getChild(i);if(child!=null)nodes.addLast(new Node(child,item.depth+1));}
                }finally{n.recycle();}
            }
            getSharedPreferences("collector",0).edit().putInt("page_"+route+"_nodes",count).putInt("page_"+route+"_texts",lines.size()).apply();
            return lines;
        }
        finally{while(!nodes.isEmpty())nodes.removeFirst().value.recycle();}
    }
    private boolean readPage(List<AccessibilityEvent> events,long epoch,String visit){
        if(!enabled()||!connected||destroyed||capturedGeneration==epoch)return false;
        if(previewGeneration==epoch&&CustomRuleRegistry.binding()!=previewBinding)return false;
        try{
            var diagnostics=getSharedPreferences("collector",0).edit().putString("page_last_attempt",Instant.now().toString()).putInt("page_root_nodes",0).putInt("page_root_texts",0).putBoolean("page_known_transfer_window",paymentWindow>=0&&!resultWindow).putBoolean("page_known_payment_result",paymentWindow>=0&&resultWindow);
            if(!events.isEmpty())diagnostics.putString("page_event_attempt",Instant.now().toString()).putInt("page_source_nodes",0).putInt("page_source_texts",0).putInt("page_source_attempts",events.size());
            diagnostics.apply();
            if(isAlipay()){
                if(paymentWindow>=0&&getSharedPreferences("collector",0).getBoolean("page_ocr_enabled",false))windowOcr.request(epoch,paymentWindow,visit,resultWindow);
                return false;
            }
            if(customWindow&&(CustomRuleRegistry.binding()!=null||!WechatCaptureWindow.allowed(currentWindowClass))){
                if(getSharedPreferences("collector",0).getBoolean("page_ocr_enabled",false))windowOcr.request(epoch,paymentWindow,visit,false);
                return false;
            }
            sensitiveNodes=false;
            List<PageReadPipeline.Reader> sources=new ArrayList<>();
            for(AccessibilityEvent event:events)sources.add(()->readNodes(event.getSource(),"source"));
            String[] route={"event-source"};
            JSONObject candidate=PageReadPipeline.read(sources,()->{route[0]="active-root";return readNodes(getRootInActiveWindow(),"root");},lines->parsePage("com.tencent.mm",lines,System.currentTimeMillis(),visit));
            if(!current(epoch,-1))return false;
            if(candidate!=null){candidate.put("captureMethod",route[0]);accept(candidate,epoch);return false;}
            var prefs=getSharedPreferences("collector",0);
            boolean empty=prefs.getInt("page_source_texts",0)+prefs.getInt("page_root_texts",0)==0;
            status(empty?"unreadable":"ignored",empty?"微信事件源与根节点未提供可用文字；可尝试窗口 OCR 增强":"");
            if(!sensitiveNodes&&prefs.getBoolean("page_ocr_enabled",false)&&paymentWindow>=0)windowOcr.request(epoch,paymentWindow,visit,resultWindow);
            return empty;
        }catch(Exception error){status("error","页面读取失败："+error.getClass().getSimpleName());return false;}
    }
    @SuppressWarnings("deprecation") boolean current(long epoch,int window){
        if(destroyed||!enabledPackage(currentPackage)||!connected||epoch!=generation)return false;
        if(previewGeneration==epoch&&CustomRuleRegistry.binding()!=previewBinding)return false;
        if(customWindow&&!hasCustomRule(currentWindowClass)&&CustomRuleRegistry.binding()==null)return false;
        if(window>=0&&(paymentWindow!=window||!getSharedPreferences("collector",0).getBoolean("page_ocr_enabled",false)))return false;
        AccessibilityNodeInfo active=getRootInActiveWindow();
        if(active==null)return false;
        try{return currentPackage.contentEquals(active.getPackageName()==null?"":active.getPackageName())&&(window<0||active.getWindowId()==window);}finally{active.recycle();}
    }
    private JSONArray customRules(){try{return new JSONArray(getSharedPreferences("collector",0).getString("custom_rules_active","[]"));}catch(Exception e){return new JSONArray();}}
    private boolean hasCustomRule(String name){try{JSONArray rules=customRules();for(int i=0;i<rules.length();i++){JSONObject r=rules.getJSONObject(i);if(CustomPaymentRule.packageFor(r.optString("origin")).equals(currentPackage)&&name.equals(r.optString("windowClass"))&&CustomRuleRegistry.bound(this,r))return true;}}catch(Exception ignored){}return false;}
    boolean isAlipay(){return AlipayCaptureWindow.PKG.equals(currentPackage);}

    boolean safeForWindowOcr(long epoch,int window){
        if(!current(epoch,window))return false;
        sensitiveNodes=false;readNodes(getRootInActiveWindow(),"ocr-preflight");
        return !sensitiveNodes&&current(epoch,window);
    }
    CapturedPayment freezeCapture(long epoch,int window,String visit,boolean result)throws Exception{
        synchronized(eventLock){
            if(!needsWindowOcr(epoch,window))return null;
            return new CapturedPayment(this,epoch,window,visit,currentPackage,currentWindowClass,result,customWindow,
                previewGeneration==epoch?previewBinding:null,captureLifecycle,System.currentTimeMillis());
        }
    }
    boolean canProcessCaptured(CapturedPayment frame){
        if(frame==null||!frame.active(this,captureLifecycle,connected&&!destroyed)||capturedGeneration==frame.epoch)return false;
        // Rule binding is a preview, never a detached payment job.
        return frame.binding==null||frame.binding==CustomRuleRegistry.binding()&&current(frame.epoch,frame.window);
    }
    boolean canPromptCaptured(CapturedPayment frame){
        return frame!=null&&frame.binding==null&&frame.authorized(this,captureLifecycle,connected&&!destroyed)&&CapturePrompt.enabled(this);
    }
    enum CustomCapture { TESTED, CAPTURED, STALE }
    static final class NoCustomMatch extends IllegalArgumentException {NoCustomMatch(String message){super(message);}}
    CustomCapture applyCustomOcr(JSONObject document,CapturedPayment frame) throws Exception {
        if(!canProcessCaptured(frame))return CustomCapture.STALE;
        if(frame.binding!=null){
            try{JSONObject candidate=CustomRuleRegistry.finishBinding(this,frame.binding,frame.windowClass,document);if(candidate==null)return CustomCapture.STALE;capturedGeneration=frame.epoch;status("rule-tested","真实窗口测试通过，尚未入账；回到自定义规则核对并启用");return CustomCapture.TESTED;}
            catch(Exception error){CustomRuleRegistry.bindingError(this,error.getMessage());throw error;}
        }
        JSONArray rules=frame.rules();JSONObject selected=null;String lastError="未匹配已启用规则";
        for(int i=0;i<rules.length();i++){
            JSONObject rule=rules.getJSONObject(i);if(!CustomPaymentRule.packageFor(rule.optString("origin")).equals(frame.packageName)||!frame.windowClass.equals(rule.optString("windowClass"))||!CustomRuleRegistry.bound(this,rule))continue;
            JSONObject candidate;try{candidate=CustomPaymentRule.evaluate(rule,document,frame.observedAt,frame.session,frame.zone);}catch(Exception error){lastError=error.getMessage();continue;}
            if(selected!=null&&(selected.getLong("amount")!=candidate.getLong("amount")||!selected.optString("merchant").equals(candidate.optString("merchant"))||!selected.optString("reference").equals(candidate.optString("reference"))))throw new IllegalArgumentException("多个自定义规则结果冲突，未自动记账，请停用冲突规则");
            selected=candidate;
        }
        if(selected==null)throw new NoCustomMatch(lastError);
        return acceptCaptured(selected,frame)?CustomCapture.CAPTURED:CustomCapture.STALE;
    }
    boolean needsWindowOcr(long epoch,int window){return capturedGeneration!=epoch&&current(epoch,window);}
    boolean reviewCustomOcr(JSONObject document,CapturedPayment frame)throws Exception{
        if(!canProcessCaptured(frame)||frame.binding!=null)return false;
        JSONArray rules=frame.rules();
        for(int i=0;i<rules.length();i++){
            JSONObject rule=rules.getJSONObject(i);if(!CustomPaymentRule.packageFor(rule.optString("origin")).equals(frame.packageName)||!frame.windowClass.equals(rule.optString("windowClass"))||!CustomRuleRegistry.bound(this,rule))continue;
            JSONObject candidate;
            try{candidate=CustomPaymentRule.reviewCandidate(rule,document,frame.observedAt,frame.session,frame.zone);}catch(Exception ignored){continue;}
            if(candidate!=null)return acceptCaptured(candidate,frame);
        }
        return false;
    }
    private void enqueue(JSONObject candidate)throws Exception{
        String fingerprint=candidate.getString("fingerprint");
        if(!fingerprint.equals(lastQueuedFingerprint)){store.enqueue(candidate);AutoLedgerProcessor.schedule(this);lastQueuedFingerprint=fingerprint;}
        getSharedPreferences("collector",0).edit().putString("page_last",Instant.now().toString()).putString("page_method",candidate.optString("captureMethod")).apply();status("pending","");
    }
    boolean acceptCaptured(JSONObject candidate,CapturedPayment frame)throws Exception{
        if(!canProcessCaptured(frame)||!frame.origin.equals(candidate.optString("origin")))return false;
        return frame.deliver(()->{enqueue(candidate);if(generation==frame.epoch)capturedGeneration=frame.epoch;});
    }
    boolean accept(JSONObject candidate,long epoch) throws Exception {
        if(capturedGeneration==epoch||!current(epoch,-1))return false;
        enqueue(candidate);capturedGeneration=epoch;return true;
    }
    static JSONObject parsePage(String pkg,List<String> lines,long collected,String session) throws Exception {
        if(!"com.tencent.mm".equals(pkg)||lines==null||lines.size()>200)return null;
        String text=String.join("\n",lines);if(text.length()>8192||Pattern.compile("验证码|动态密码|密码|口令").matcher(text).find())return null;
        for(String line:lines)if(line.matches("发送|按住说话|聊天信息|通讯录|朋友圈"))return null;
        JSONObject paidResult=parsePaidTransferResult(lines,text,collected,session);
        if(paidResult!=null)return WechatPaymentPage.accessible(paidResult,lines,session);
        JSONObject merchantResult=parsePaidMerchantResult(lines,text,collected,session);
        if(merchantResult!=null)return WechatPaymentPage.accessible(merchantResult,lines,session);
        JSONObject senderDetail=WechatPaymentPage.pendingDetail(lines,collected);
        if(senderDetail!=null){
            senderDetail.put("reliable",false).put("reason","付款方已转出、对方待收款；按页面转账时间记录，待补用途。");
            return WechatPaymentPage.accessible(senderDetail,lines,session);
        }
        int header=-1;for(int i=0;i<Math.min(lines.size(),12);i++)if(lines.get(i).matches("微信转账|转账详情")){header=i;break;}
        if(header<0||!Pattern.compile("转账时间|收款时间|转账单号|交易单号").matcher(text).find())return null;
        if(!Pattern.compile("转账成功|已收款|已收钱|待收款|待领取|等待[^\\n]*(?:收款|收钱)|已退回|转账失败|已取消|已关闭|处理中").matcher(text).find())return null;
        // A standalone currency amount is a role only next to this narrowly gated transfer header.
        // Never take the maximum number, a balance, fee, total, or another person's red-packet share.
        List<String> money=new ArrayList<>();
        for(int i=0;i<lines.size();i++)if(lines.get(i).matches("[￥¥][ \\t]*[0-9]+(?:\\.[0-9]{1,2})?")){
            String prior=i==0?"":lines.get(i-1);
            if(i>header&&i<=header+5&&!prior.matches(".*(余额|原价|优惠|总额|剩余|手续费|退款).*"))money.add(lines.get(i));
            else money.add("ambiguous");
        }
        String normalized=text;
        if(money.size()==1&&!money.get(0).equals("ambiguous")&&!Pattern.compile("转账金额|转出金额|退款金额|已退回").matcher(text).find())normalized+="\n转账金额："+money.get(0);
        JSONObject c=PaymentListener.parse(pkg,"微信支付",normalized,"page",collected);if(c==null)return null;
        String observed=Instant.ofEpochMilli(collected).toString(),time=PaymentListener.match(text,"(?:转账时间)\\s*[:：]?\\s*(\\d{4}[-/]\\d{2}[-/]\\d{2} [0-2]\\d:[0-5]\\d(?::[0-5]\\d)?)");
        String occurred=observed,basis="page-observed-time";
        if(!time.isEmpty()){try{String value=time.replace('/','-');if(value.length()==16)value+=":00";occurred=LocalDateTime.parse(value,DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)).atZone(ZoneId.systemDefault()).toInstant().toString();basis="page-local-time-device-zone";}catch(Exception ignored){time="";}}
        c.put("source","accessibility");c.put("reliable",false);c.put("merchant","");c.put("category",JSONObject.NULL);c.put("occurredAt",occurred);c.put("collectedAt",observed);c.put("timeBasis",basis);
        // Without a reliable transaction ID, separate visits must not silently merge
        // two real transfers merely because amount and displayed second coincide.
        String identity=c.getString("reference");if(identity.isEmpty())identity="session:"+session+"|time:"+time;
        c.put("fingerprint",PaymentListener.digest("accessibility|wechat|"+identity+"|"+c.opt("amount")+"|"+c.getString("type")+"|"+c.getBoolean("successful")+"|"+c.getString("status")));
        c.put("reason",c.getString("status").equals("rejected")?"页面显示失败或取消，不入账":"实验性页面识别；请核对金额、本人收支方向、用途与发生时间。转账不会直接当作消费。");
        return c;
    }
    /** Sender payment succeeded; recipient acceptance is a separate pending state. */
    private static JSONObject parsePaidTransferResult(List<String> lines,String text,long collected,String session) throws Exception {
        if(Pattern.compile("支付失败|转账失败|交易失败|取消|关闭|处理中|待支付|退款|退回|红包|余额|原价|优惠|手续费|总额|剩余|待(?:你|您|本人|自己|我)确认收款").matcher(text).find())return null;
        int header=-1,waiting=-1,done=-1,moneyIndex=-1;String currency="";int moneyCount=0,waitingCount=0;
        for(int i=0;i<lines.size();i++){
            String line=lines.get(i).trim();
            if(i<12&&line.equals("支付成功"))header=i;
            if(line.matches("待.{1,160}确认收款")){waiting=i;waitingCount++;}
            if(line.equals("完成"))done=i;
            // OCR / accessible nodes may omit the currency glyph. Only a full
            // two-decimal token gets that fallback role inside this strict result context.
            // Count every such token, including conflicts elsewhere on the page.
            if(line.matches("[￥¥][ \\t]*[0-9]+(?:\\.[0-9]{1,2})?")||line.matches("[0-9]+\\.[0-9]{2}")){currency=line;moneyIndex=i;moneyCount++;}
        }
        if(header<0||waitingCount!=1||moneyCount!=1||waiting<=header||waiting>header+4||moneyIndex<=waiting||moneyIndex>waiting+3||done<=moneyIndex)return null;
        // Sanitize before using the common parser: no recipient nickname or unrelated page text.
        String amountToken=currency.startsWith("￥")||currency.startsWith("¥")?currency:"¥"+currency;
        JSONObject c=PaymentListener.parse("com.tencent.mm","微信支付","转账\n支付成功\n转账金额："+amountToken,"page-result",collected);
        if(c==null||c.isNull("amount"))return null;
        String observed=Instant.ofEpochMilli(collected).toString();
        c.put("source","accessibility");c.put("type","complex");c.put("status","pending");c.put("reliable",false);
        c.put("successful",true);c.put("direction","out");c.put("fundsStatus","paid_out");c.put("recipientStatus","pending");
        c.put("evidence","wechat-paid-transfer-result-v1");
        c.put("merchant","");c.put("category",JSONObject.NULL);c.put("reference","");c.put("occurredAt",observed);c.put("collectedAt",observed);c.put("timeBasis","page-observed-time");
        c.put("fingerprint",PaymentListener.digest("accessibility|wechat|paid-transfer-result|session:"+session+"|"+c.getLong("amount")));
        c.put("reason","页面显示已支付，待对方确认收款；已提取转出金额，请核对用途和交易时间。用于消费时选择「消费」并确认，借款或还款不要当作消费。");
        return c;
    }
    private static JSONObject parsePaidMerchantResult(List<String> lines,String text,long collected,String session) throws Exception {
        return WechatMerchantResult.parse(lines,collected,false);
    }
    @Override public void onDestroy(){synchronized(eventLock){destroyed=true;connected=false;captureLifecycle++;generation++;while(!pendingEvents.isEmpty())pendingEvents.removeFirst().recycle();handler.removeCallbacksAndMessages(null);}handler.post(()->{windowOcr.close();store.close();});thread.quitSafely();super.onDestroy();}
}

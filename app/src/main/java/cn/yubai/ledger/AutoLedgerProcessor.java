package cn.yubai.ledger;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.WebViewAssetLoader;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;

/** Event-driven, short-lived local JS worker. Uses the same ledger reducer as the UI. */
public final class AutoLedgerProcessor {
    static final String ACTION_UPDATED="cn.yubai.ledger.LEDGER_UPDATED";
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static AutoLedgerProcessor current;
    private static boolean requested;
    private final Context context;
    private final Store store;
    private final Runnable completed;
    private final boolean managed;
    private WebView web;
    private boolean finished;
    private final Runnable timeout=()->finish("error","自动处理超时，采集记录已保留，下次事件或打开应用时重试");

    private AutoLedgerProcessor(Context context,boolean managed,Runnable completed){this.context=context;this.store=new Store(context);this.managed=managed;this.completed=completed;}
    public static void schedule(Context context){
        Context app=context.getApplicationContext();
        MAIN.post(()->{requested=true;if(current==null)startManaged(app);});
    }
    private static void startManaged(Context context){requested=false;current=new AutoLedgerProcessor(context,true,null);current.start();}
    // Instrumentation supplies a database-isolated Context; no production ledger is touched.
    static void runIsolated(Context context,Runnable completed){MAIN.post(()->new AutoLedgerProcessor(context,false,completed).start());}
    private void state(String value,String error){
        var edit=context.getSharedPreferences("collector",0).edit().putString("processor_state",value).putString("processor_error",error);
        if(value.equals("updated"))edit.putString("processor_last",Instant.now().toString());edit.apply();
    }
    private void start(){
        try{
            if(store.load().isEmpty()||new JSONArray(store.inbox()).length()==0){finish("idle","");return;}
            state("processing","");
            web=new WebView(context);web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(false);
            web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);
            web.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            WebViewAssetLoader loader=new WebViewAssetLoader.Builder().addPathHandler("/assets/",new WebViewAssetLoader.AssetsPathHandler(context)).build();
            web.setWebViewClient(new WebViewClient(){
                @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                    Uri uri=request.getUrl();
                    if("https".equals(uri.getScheme())&&"appassets.androidplatform.net".equals(uri.getHost())&&uri.getPath()!=null&&uri.getPath().startsWith("/assets/web/"))return loader.shouldInterceptRequest(uri);
                    return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",java.util.Collections.emptyMap(),new java.io.ByteArrayInputStream(new byte[0]));
                }
                @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}
            });
            web.addJavascriptInterface(new Bridge(),"Processor");
            MAIN.postDelayed(timeout,30_000);
            web.loadUrl("https://appassets.androidplatform.net/assets/web/processor.html");
        }catch(Exception error){finish("error","自动处理启动失败："+error.getClass().getSimpleName()+"；采集记录已保留");}
    }
    public final class Bridge {
        @JavascriptInterface public String snapshot(){
            try{return new JSONObject().put("state",store.load()).put("queue",new JSONArray(store.inbox())).toString();}
            catch(Exception error){return "{\"error\":\"无法读取采集队列\"}";}
        }
        @JavascriptInterface public String commit(long expected,String body,String ids){
            try{return store.commit(expected,body,ids)?"ok":"conflict";}
            catch(Exception error){return "自动处理保存失败："+error.getClass().getSimpleName();}
        }
        @JavascriptInterface public void done(String value,String message){MAIN.post(()->finish(value.equals("updated")?"updated":value.equals("idle")?"idle":"error",message));}
    }
    private void finish(String value,String error){
        if(finished)return;finished=true;MAIN.removeCallbacks(timeout);
        boolean more=false;
        try{if(value.equals("updated")){more=new JSONArray(store.inbox()).length()>0;PageCaptureService.syncSettings(context,store.load());}}catch(Exception ignored){}
        state(value,error);
        if(web!=null){web.removeJavascriptInterface("Processor");web.stopLoading();web.destroy();web=null;}
        store.close();
        if(value.equals("updated"))context.sendBroadcast(new Intent(ACTION_UPDATED).setPackage(context.getPackageName()));
        if(managed){current=null;if((requested||more)&&!value.equals("error"))MAIN.post(()->{if(current==null)startManaged(context);});}
        if(completed!=null)completed.run();
    }
}

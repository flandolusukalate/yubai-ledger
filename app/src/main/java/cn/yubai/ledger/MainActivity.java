package cn.yubai.ledger;

import android.app.Activity;
import android.content.Intent;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.WindowInsets;
import android.widget.Toast;
import androidx.webkit.WebViewAssetLoader;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public final class MainActivity extends androidx.activity.ComponentActivity {
    static boolean containsComponent(String raw,ComponentName expected){if(raw==null)return false;for(String token:raw.split(":")){ComponentName component=ComponentName.unflattenFromString(token);if(expected.equals(component))return true;}return false;}
    private WebView web;
    private Store store;
    private Uri sharedImage;
    private final RuleImageSession ruleImages=new RuleImageSession();
    private String ruleImageRequest="";
    private boolean ready=false;
    private boolean foreground=false,receiverRegistered=false;
    private final androidx.activity.result.ActivityResultLauncher<String> capturePromptPermission=registerForActivityResult(
        new androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),granted->dispatch("resume",new JSONObject()));
    private final BroadcastReceiver ledgerReceiver=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){if(foreground&&AutoLedgerProcessor.ACTION_UPDATED.equals(intent.getAction()))dispatch("ledger-updated",new JSONObject());}};
    private androidx.activity.OnBackPressedCallback backCallback;
    private String pendingExport="",pendingKind="";
    private final java.util.concurrent.ExecutorService worker=Executors.newSingleThreadExecutor();
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);store=new Store(this);web=new WebView(this);
        IntentFilter updates=new IntentFilter(AutoLedgerProcessor.ACTION_UPDATED);
        androidx.core.content.ContextCompat.registerReceiver(this,ledgerReceiver,updates,androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered=true;
        android.widget.FrameLayout root=new android.widget.FrameLayout(this);root.setBackgroundColor(0xffeee8dc);root.addView(web,new android.widget.FrameLayout.LayoutParams(-1,-1));setContentView(root);
        backCallback=new androidx.activity.OnBackPressedCallback(false){@Override public void handleOnBackPressed(){web.evaluateJavascript("window.handleBack && window.handleBack()",null);}};
        getOnBackPressedDispatcher().addCallback(this,backCallback);
        if(saved!=null){pendingExport=saved.getString("export","");pendingKind=saved.getString("kind","");}
        web.setBackgroundColor(0xffeee8dc);
        root.setOnApplyWindowInsetsListener((v,insets)->{int top,bottom;
            if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());top=i.top;bottom=i.bottom;}
            else{top=insets.getSystemWindowInsetTop();bottom=insets.getSystemWindowInsetBottom();}
            v.setPadding(0,top,0,bottom);return insets;});
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(false);
        web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);
        web.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // Every page is a bundled asset: never serve a cached ui.mjs/css from the previous install.
        web.getSettings().setCacheMode(android.webkit.WebSettings.LOAD_NO_CACHE);
        try{long stamp=getPackageManager().getPackageInfo(getPackageName(),0).lastUpdateTime;android.content.SharedPreferences prefs=getSharedPreferences("yubai-web",MODE_PRIVATE);
            if(prefs.getLong("assetsStamp",0)!=stamp){web.clearCache(true);prefs.edit().putLong("assetsStamp",stamp).apply();}}catch(Exception ignored){}
        WebViewAssetLoader loader=new WebViewAssetLoader.Builder().addPathHandler("/assets/",new WebViewAssetLoader.AssetsPathHandler(this)).build();
        web.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                Uri uri=request.getUrl();
                if("https".equals(uri.getScheme())&&"appassets.androidplatform.net".equals(uri.getHost()))return loader.shouldInterceptRequest(uri);
                return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",java.util.Collections.emptyMap(),new java.io.ByteArrayInputStream(new byte[0]));
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}
            @Override public void onPageFinished(WebView view,String url){ready=true;dispatch("resume",new JSONObject());if(sharedImage!=null){Uri u=sharedImage;sharedImage=null;recognize(u);}}
        });
        web.addJavascriptInterface(new Bridge(),"Native");
        handleIntent(getIntent());web.loadUrl("https://appassets.androidplatform.net/assets/web/index.html");
    }
    @Override public void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleIntent(intent);if(ready&&sharedImage!=null){Uri u=sharedImage;sharedImage=null;recognize(u);}}
    private void handleIntent(Intent intent){if(Intent.ACTION_SEND.equals(intent.getAction())&&intent.getType()!=null&&intent.getType().startsWith("image/"))sharedImage=intent.getParcelableExtra(Intent.EXTRA_STREAM);}
    @Override public void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);/* Export content lives in SQLite/file, not a large Binder bundle. */out.putString("kind",pendingKind);}
    @Override public void onResume(){super.onResume();foreground=true;AutoLedgerProcessor.schedule(this);if(ready)dispatch("resume",new JSONObject());}
    @Override public void onPause(){foreground=false;super.onPause();}
    private void dispatch(String type,JSONObject value){if(isFinishing()||isDestroyed()||!ready)return;runOnUiThread(()->{if(!isFinishing()&&!isDestroyed()&&ready)web.evaluateJavascript("window.nativeEvent && window.nativeEvent("+JSONObject.quote(type)+","+value+")",null);});}
    private void error(String message){try{dispatch("error",new JSONObject().put("message",message));}catch(Exception ignored){}}
    private void recognize(Uri uri){
        dispatch("ocr-start",new JSONObject());
        worker.execute(()->{
            try {
                InputImage image=InputImage.fromFilePath(this,uri);
                com.google.mlkit.vision.text.TextRecognizer recognizer=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
                recognizer.process(image).addOnSuccessListener(result->{try{dispatch("ocr",new JSONObject().put("text",result.getText()).put("origin","unknown"));}catch(Exception e){error("OCR 结果读取失败");}})
                    .addOnFailureListener(ex->error("本地 OCR 失败，请换一张清晰截图或手动记账"))
                    .addOnCompleteListener(task->recognizer.close());
            }catch(Exception ex){error("无法读取所选图片");}
        });
    }
    private void pick(int code,String mime){runOnUiThread(()->{Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType(mime);startActivityForResult(intent,code);});}
    @Override public void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK||data==null||data.getData()==null){if(request==40){try{dispatch("rule-image-cancel",new JSONObject().put("sampleId",ruleImageRequest));}catch(Exception ignored){}}return;}
        Uri uri=data.getData();if(request==40){ruleImages.load(this,uri,ruleImageRequest,worker,this::dispatch);return;}if(request==10){recognize(uri);return;}
        worker.execute(()->{try{
            if(request==20){byte[] bytes=java.nio.file.Files.readAllBytes(new java.io.File(getCacheDir(),"pending-export").toPath());try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new Exception();out.write(bytes);}dispatch("saved",new JSONObject());return;}
            String text;try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new Exception();java.io.ByteArrayOutputStream buffer=new java.io.ByteArrayOutputStream();byte[] chunk=new byte[8192];int count;while((count=in.read(chunk))!=-1){if(buffer.size()+count>20_000_000)throw new Exception("too large");buffer.write(chunk,0,count);}text=buffer.toString("UTF-8");}
            dispatch(request==11?"csv":"backup",new JSONObject().put("text",text));
        }catch(Exception ex){error("文件读取或保存失败；原账本保持不变");}});
    }
    public final class Bridge {
        @JavascriptInterface public String load(){try{return store.load();}catch(Exception e){return "{\"loadError\":true}";}}
        @JavascriptInterface public String save(String json){try{return commit(new JSONObject(json).getLong("revision")-1,json,"[]");}catch(Exception e){return "保存失败，原账本未更改";}}
        @JavascriptInterface public String commit(long expected,String json,String ids){try{CustomRuleRegistry.validateCommit(MainActivity.this,json);if(!store.commit(expected,json,ids))return "conflict";PageCaptureService.syncSettings(MainActivity.this,json);AutoLedgerProcessor.schedule(MainActivity.this);return "ok";}catch(Exception e){return e instanceof IllegalArgumentException?e.getMessage():"保存失败，原账本未更改";}}
        @JavascriptInterface public String replace(String json){try{String saved=store.load();return replaceCAS(saved.isEmpty()?0:new JSONObject(saved).optLong("revision",0),json);}catch(Exception e){return "恢复失败，原账本未更改";}}
        @JavascriptInterface public String replaceCAS(long expected,String json){try{json=CustomRuleRegistry.forRestore(json);if(!store.replaceCAS(expected,json))return "conflict";CustomRuleRegistry.resetProofs(MainActivity.this);PageCaptureService.syncSettings(MainActivity.this,store.load());AutoLedgerProcessor.schedule(MainActivity.this);return "ok";}catch(Exception e){return "恢复失败，原账本未更改";}}
        @JavascriptInterface public String replaceCorrupt(String expectedRaw,String json){try{json=CustomRuleRegistry.forRestore(json);if(!store.replaceCorrupt(expectedRaw,json))return "conflict";CustomRuleRegistry.resetProofs(MainActivity.this);PageCaptureService.syncSettings(MainActivity.this,store.load());AutoLedgerProcessor.schedule(MainActivity.this);return "ok";}catch(Exception e){return "恢复失败，原账本未更改";}}
        @JavascriptInterface public void processQueue(){AutoLedgerProcessor.schedule(MainActivity.this);}
        @JavascriptInterface public String recovery(){return store.recovery();}
        @JavascriptInterface public void backEnabled(boolean enabled){runOnUiThread(()->backCallback.setEnabled(enabled));}
        @JavascriptInterface public String queue(){try{return store.inbox();}catch(Exception e){return "[]";}}
        @JavascriptInterface public String ack(String ids){try{store.acknowledge(ids);return "ok";}catch(Exception e){return "error";}}
        @JavascriptInterface public String status(){try{
            String granted=Settings.Secure.getString(getContentResolver(),"enabled_notification_listeners");ComponentName component=new ComponentName(MainActivity.this,PaymentListener.class);
            String access=Settings.Secure.getString(getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);ComponentName accessComponent=new ComponentName(MainActivity.this,PageCaptureService.class);
            return new JSONObject().put("native",true).put("authorized",containsComponent(granted,component)).put("connected",PaymentListener.connected)
                .put("capturePromptEnabled",CapturePrompt.enabled(MainActivity.this))
                .put("capturePromptState",getSharedPreferences("collector",0).getString("capture_prompt_state","idle"))
                .put("accessibilityAuthorized",Settings.Secure.getInt(getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,0)==1&&containsComponent(access,accessComponent)).put("accessibilityConnected",PageCaptureService.connected)
                .put("accessibilityLast",getSharedPreferences("collector",0).getString("page_last","")).put("accessibilityError",getSharedPreferences("collector",0).getString("page_error","")).put("accessibilityState",getSharedPreferences("collector",0).getString("page_state","idle"))
                .put("pageOcrSupported",android.os.Build.VERSION.SDK_INT>=34).put("pageOcrState",getSharedPreferences("collector",0).getString("page_ocr_state","idle")).put("pageOcrMessage",getSharedPreferences("collector",0).getString("page_ocr_message",""))
                .put("pageSourcePackage",getSharedPreferences("collector",0).getString("page_source_package","")).put("pageMethod",getSharedPreferences("collector",0).getString("page_method","")).put("pageAttempt",getSharedPreferences("collector",0).getString("page_last_attempt",""))
                .put("pageKnownResult",getSharedPreferences("collector",0).getBoolean("page_known_payment_result",false)).put("pageOcrAttempts",getSharedPreferences("collector",0).getInt("page_ocr_attempts",0)).put("pageOcrLast",getSharedPreferences("collector",0).getString("page_ocr_last",""))
                .put("pageSourceTexts",getSharedPreferences("collector",0).getInt("page_source_texts",0)).put("pageRootTexts",getSharedPreferences("collector",0).getInt("page_root_texts",0))
                .put("processorState",getSharedPreferences("collector",0).getString("processor_state","idle")).put("processorLast",getSharedPreferences("collector",0).getString("processor_last","")).put("processorError",getSharedPreferences("collector",0).getString("processor_error",""))
                .put("last",getSharedPreferences("collector",0).getString("last","")).put("error",getSharedPreferences("collector",0).getString("error","")).toString();
        }catch(Exception e){return "{}";}}
        @JavascriptInterface public void notificationSettings(){runOnUiThread(()->startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));}
        @JavascriptInterface public void capturePromptPermission(){runOnUiThread(()->{
            if(android.os.Build.VERSION.SDK_INT>=33&&!CapturePrompt.enabled(MainActivity.this)
                &&(!getSharedPreferences("display",0).getBoolean("notification_asked",false)||shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS))){
                getSharedPreferences("display",0).edit().putBoolean("notification_asked",true).apply();
                capturePromptPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS);
            }else startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()));
        });}
        @JavascriptInterface public void openAccessibilitySettings(){runOnUiThread(()->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));}
        @JavascriptInterface public void pickRuleImage(String request){if(request==null||!request.matches("[A-Za-z0-9_-]{1,80}"))return;ruleImageRequest=request;pick(40,"image/*");}
        @JavascriptInterface public void clearRuleImage(){ruleImages.clear();}
        @JavascriptInterface public String testCustomRule(String request,String json){try{return ruleImages.test(MainActivity.this,request,new JSONObject(json)).toString();}catch(Exception e){return ruleError(e);}}
        @JavascriptInterface public String customRuleStatus(){try{return CustomRuleRegistry.status(MainActivity.this,store.load()).toString();}catch(Exception e){return ruleError(e);}}
        @JavascriptInterface public String bindCustomRule(String json,boolean consent){try{
            if(!consent)throw new IllegalArgumentException("请先确认真实窗口的读取范围");
            var p=getSharedPreferences("collector",0);JSONObject rule=new JSONObject(json);String source=rule.optString("origin").equals("alipay")?"page_capture_alipay":"page_capture_wechat";
            if(!PageCaptureService.connected||!p.getBoolean("page_capture_enabled",false)||!p.getBoolean(source,false)||!p.getBoolean("page_ocr_enabled",false))throw new IllegalArgumentException("请先在设置开启对应来源的页面识别和窗口 OCR，并确认服务已连接");
            if(android.os.Build.VERSION.SDK_INT<34)throw new IllegalArgumentException("自动窗口识别需要 Android 14 或以上");
            return CustomRuleRegistry.begin(MainActivity.this,new JSONObject(json)).toString();
        }catch(Exception e){return ruleError(e);}}
        @JavascriptInterface public void cancelRuleBinding(){CustomRuleRegistry.cancel(MainActivity.this);}
        @JavascriptInterface public String deepSeekStatus(){try{return DeepSeekSettings.status(MainActivity.this).toString();}catch(Exception e){return "{}";}}
        @JavascriptInterface public String configureDeepSeek(boolean enabled,String key,boolean forget,boolean consent){try{DeepSeekSettings.configure(MainActivity.this,enabled,key,forget,consent);return "ok";}catch(Exception e){return e instanceof IllegalArgumentException?e.getMessage():"密钥加密或保存失败，设置未生效";}}
        @JavascriptInterface public void requestAiRuleImage(String request,String token,String json){worker.execute(()->{try{
            if(!ruleImages.ai(MainActivity.this,request,token,new JSONObject(json),MainActivity.this::dispatch))dispatch("rule-image-ai-skipped",new JSONObject().put("sampleId",request).put("token",token));
        }catch(Exception e){try{dispatch("rule-image-ai-error",new JSONObject().put("sampleId",request).put("token",token).put("message","云端辅助未启动，请检查设置与截图标记"));}catch(Exception ignored){}}});}
        private String ruleError(Exception e){try{return new JSONObject().put("ok",false).put("error",e.getMessage()==null?"规则操作失败":e.getMessage()).toString();}catch(Exception ignored){return "{}";}}
        @JavascriptInterface public void pickImage(){pick(10,"image/*");}
        @JavascriptInterface public void pickCSV(){pick(11,"*/*");}
        @JavascriptInterface public void pickBackup(){pick(12,"application/json");}
        @JavascriptInterface public void exportFile(String text,String name,String mime){
            try{if(text.length()>20_000_000)throw new Exception();java.nio.file.Files.write(new java.io.File(getCacheDir(),"pending-export").toPath(),text.getBytes(StandardCharsets.UTF_8));}
            catch(Exception e){error("无法准备导出文件");return;}
            runOnUiThread(()->{Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType(mime);intent.putExtra(Intent.EXTRA_TITLE,name);startActivityForResult(intent,20);});
        }
    }
    @Override public void onDestroy(){ruleImages.clear();ready=false;if(receiverRegistered){unregisterReceiver(ledgerReceiver);receiverRegistered=false;}web.removeJavascriptInterface("Native");web.destroy();worker.shutdown();store.close();super.onDestroy();}
}

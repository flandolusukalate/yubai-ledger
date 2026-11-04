package cn.yubai.ledger;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.time.Instant;
import java.util.concurrent.Executor;
import org.json.JSONObject;

/** Optional window-only capture, one high-resolution local pass per known result/detail visit. */
final class PageWindowOcr {
    private final PageCaptureService service;
    private final Handler worker;
    private final Executor executor;
    private TextRecognizer recognizer;
    private long requested=-1;
    private Request pending;
    private static final class Request {
        final long epoch;final int window;final String session;final boolean result;
        CapturedPayment frame;
        Request(long e,int w,String s,boolean r){epoch=e;window=w;session=s;result=r;}
    }
    private boolean busy,recognizing;
    private volatile boolean closed;
    static int maxEdge(){return 2880;}
    private boolean current(Request request){
        return !closed&&(request.frame==null?service.needsWindowOcr(request.epoch,request.window):service.canProcessCaptured(request.frame));
    }
    PageWindowOcr(PageCaptureService service,Handler worker){
        this.service=service;this.worker=worker;
        executor=action->{if(!worker.post(action))action.run();};
    }
    private void state(String value,String message){
        if(closed)return;
        service.getSharedPreferences("collector",0).edit().putString("page_ocr_state",value).putString("page_ocr_message",message).putString("page_ocr_last",Instant.now().toString()).apply();
    }
    private boolean fallback(Request request,JSONObject document,Bitmap image)throws Exception {
        if(document==null||!current(request)||request.frame==null||request.frame.binding!=null)return false;
        if(DeepSeekSettings.enabled(service)&&DeepSeekReceipt.safeImage(document,true)){
            long cloudRevision=DeepSeekSettings.revision(service);byte[] jpeg=DeepSeekFallback.image(image);String imageKey=PaymentListener.digest(android.util.Base64.encodeToString(jpeg,android.util.Base64.NO_WRAP));
            boolean started;try{started=DeepSeekFallback.requestCaptured(service,jpeg,document,request.frame,imageKey,()->current(request),
                candidate->worker.post(()->{try{
                    if(!current(request)||!DeepSeekSettings.enabled(service)||cloudRevision!=DeepSeekSettings.revision(service))return;
                    JSONObject payment=WindowAiPayment.qualify(candidate,document,request.result);
                    if(service.acceptCaptured(payment,request.frame)){
                        boolean paid=payment.optString("captureMethod").equals("accessibility-window-ai");
                        String message=paid?"DeepSeek 已提取明确付款，正在统一查重；用途可稍后补充":"DeepSeek 结果仍不明确，已保留待核对；没有扣款";
                        state(paid?"captured":"needs-review",message);
                        DeepSeekSettings.prefs(service).edit().putString("state",paid?"captured":"review").putString("message",message).apply();
                    }
                }catch(Exception e){state("error","云端结果未保存，请回账本核对");}}),
                message->worker.post(()->{if(current(request)){state("unrecognized",message);try{service.reviewCustomOcr(document,request.frame);}catch(Exception ignored){}}}));
            }finally{java.util.Arrays.fill(jpeg,(byte)0);}
            if(started){state("cloud-pending","高清本地识别未通过，正在请求 DeepSeek；尚未扣款");return true;}
        }
        if(request.frame.custom&&service.reviewCustomOcr(document,request.frame)){state("needs-review","付款字段未能唯一确定，已保留待核对；没有扣款");return true;}
        return false;
    }
    void request(long epoch,int window,String session,boolean result){
        if(closed||requested>=epoch)return;
        requested=epoch;
        if(Build.VERSION.SDK_INT<34){state("unsupported","窗口 OCR 需要 Android 14 或以上；可使用选择图片识别");return;}
        Request next=new Request(epoch,window,session,result);
        if(busy){pending=next;return;}
        capture(next);
    }
    @androidx.annotation.RequiresApi(34)
    private void finish(){
        busy=false;
        if(closed)return;
        if(pending!=null){Request next=pending;pending=null;capture(next);}
    }
    @androidx.annotation.RequiresApi(34)
    private void capture(Request request){
        busy=true;
        long epoch=request.epoch;int window=request.window;
        worker.postDelayed(()->{
            if(!current(request)){finish();return;}
            if(!service.safeForWindowOcr(epoch,window)){state("blocked","页面存在输入控件、读取不完整或窗口已变化，未截图、未记账");finish();return;}
            state("capturing","正在本机识别当前"+(service.isAlipay()?"支付宝":"微信")+"付款窗口（单次高清）");
            service.getSharedPreferences("collector",0).edit().putInt("page_ocr_attempts",1).apply();
            try{
                service.takeScreenshotOfWindow(window,executor,new AccessibilityService.TakeScreenshotCallback(){
                    @Override public void onFailure(int error){if(current(request))state("blocked",failureMessage(error));finish();}
                    @Override public void onSuccess(AccessibilityService.ScreenshotResult result){read(result,request);}
                });
            }catch(Exception error){state("error","窗口截图调用失败；请重新授权或改用分享截图");finish();}
        },700);
    }
    static String failureMessage(int code){
        return switch(code){
            case AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "系统禁止截取此受保护窗口，未识别、未扣款；请手动记账或导入账单";
            case AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "窗口截图能力未授权，请在系统中关闭后重新开启余白服务";
            case AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "付款窗口已改变，请重新打开同一详情页";
            case AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "截图间隔受系统限制，请稍后重新打开详情页";
            default -> "系统未提供窗口截图（错误 "+code+"），未识别、未扣款";
        };
    }
    @androidx.annotation.RequiresApi(34)
    private void read(AccessibilityService.ScreenshotResult result,Request request){
        long epoch=request.epoch;int window=request.window;String session=request.session;
        Bitmap software=null,hardware=null;
        try(HardwareBuffer buffer=result.getHardwareBuffer()){
            if(!current(request)){finish();return;}
            request.frame=service.freezeCapture(epoch,window,session,request.result);
            if(request.frame==null){finish();return;}
            if((long)buffer.getWidth()*buffer.getHeight()>20_000_000L)throw new IllegalArgumentException("Image too large");
            hardware=Bitmap.wrapHardwareBuffer(buffer,result.getColorSpace());
            if(hardware==null)throw new IllegalStateException("Missing bitmap");
            software=hardware.copy(Bitmap.Config.ARGB_8888,false);
        }catch(Exception error){state("error","窗口图像不可用，未生成账单");finish();return;}
        finally{if(hardware!=null)hardware.recycle();}
        Bitmap input=software;
        try{
            double scale=Math.min(1,(double)maxEdge()/Math.max(input.getWidth(),input.getHeight()));
            if(scale<1){Bitmap resized=Bitmap.createScaledBitmap(input,Math.max(1,(int)(input.getWidth()*scale)),Math.max(1,(int)(input.getHeight()*scale)),true);input.recycle();input=resized;}
            if(recognizer==null)recognizer=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            final Bitmap image=input;final long started=SystemClock.uptimeMillis();
            if(request.frame.binding==null){
                state("processing-image","已截取付款图，可离开页面；正在后台识别");
                new Handler(android.os.Looper.getMainLooper()).post(()->{if(!closed&&service.canPromptCaptured(request.frame))CapturePrompt.show(service);});
            }
            Runnable timeout=()->{if(current(request))state("error","本地 OCR 超时，未生成账单；可重新打开详情页尝试");};
            worker.postDelayed(timeout,15_000);
            recognizing=true;
            recognizer.process(InputImage.fromBitmap(image,0)).addOnCompleteListener(executor,task->{
                worker.removeCallbacks(timeout);
                try{
                    if(!current(request)||SystemClock.uptimeMillis()-started>15_000)return;
                    if(!task.isSuccessful()){state("error","本地 OCR 失败，未生成账单");return;}
                    JSONObject document=RuleOcrDocument.from(task.getResult(),image.getWidth(),image.getHeight());
                    if(request.frame.custom){
                        try{
                            PageCaptureService.CustomCapture applied=service.applyCustomOcr(document,request.frame);
                            if(applied==PageCaptureService.CustomCapture.STALE)return;
                            boolean test=applied==PageCaptureService.CustomCapture.TESTED;
                            state(test?"rule-tested":"captured",test?"真实窗口测试通过，未生成账单；请回规则页核对后启用":"自定义规则已提取付款，正在统一查重；用途可稍后补充");return;
                        }catch(PageCaptureService.NoCustomMatch error){
                            if(!request.frame.builtIn){
                                if(fallback(request,document,image))return;
                                state("unrecognized",error.getMessage());return;
                            }
                            // A custom layout never disables existing supported payment layouts.
                        }catch(IllegalArgumentException error){if(fallback(request,document,image))return;state("unrecognized",error.getMessage());return;}
                    }
                    JSONObject candidate=null;
                    try{candidate=request.frame.parseBuiltIn(document,ScreenOcrText.read(task.getResult()));
                    }catch(IllegalArgumentException|java.time.DateTimeException|ArithmeticException unrecognized){/* Failed local fields use the same bounded fallback. */}
                    if(candidate==null){
                        if(fallback(request,document,image))return;
                        state("unrecognized","高清识别未能确定付款，云端未启用或此页面不适合上传；未扣款，可分享截图或手动记账");return;}
                    if(service.acceptCaptured(candidate,request.frame))state("captured","窗口 OCR 已提取付款，正在查重；用途可稍后补充");
                }catch(Exception error){state("error","识别结果保存失败，未确认扣款，请回账本核对");}
                finally{recognizing=false;image.recycle();if(closed&&recognizer!=null){recognizer.close();recognizer=null;}finish();}
            });
        }catch(Exception error){recognizing=false;if(input!=null)input.recycle();state("error","无法启动本地 OCR");finish();}
    }
    void close(){closed=true;if(!recognizing&&recognizer!=null){recognizer.close();recognizer=null;}}
}

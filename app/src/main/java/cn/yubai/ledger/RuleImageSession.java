package cn.yubai.ledger;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.util.Base64;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

/** One selected screenshot in memory; no URI grants, image files or OCR text are persisted. */
final class RuleImageSession {
    private String id="";private JSONObject document;private byte[] cloudImage;private String imageKey="";private volatile int generation;
    synchronized void clear(){generation++;id="";document=null;imageKey="";if(cloudImage!=null)java.util.Arrays.fill(cloudImage,(byte)0);cloudImage=null;}
    void load(Context context,Uri uri,String request,Executor worker,BiConsumer<String,JSONObject> dispatch){
        clear();final int epoch=generation;
        worker.execute(()->{
            Bitmap bitmap=null;
            try{
                if(Build.VERSION.SDK_INT>=28){bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.getContentResolver(),uri),(decoder,info,source)->{
                    int w=info.getSize().getWidth(),h=info.getSize().getHeight();if(w<1||h<1||(long)w*h>50_000_000L)throw new IllegalArgumentException("图片过大");
                    double scale=Math.min(1,2880.0/Math.max(w,h));decoder.setTargetSize(Math.max(1,(int)(w*scale)),Math.max(1,(int)(h*scale)));decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });}else{
                    BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
                    try(var in=context.getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,options);}
                    if(options.outWidth<1||options.outHeight<1||(long)options.outWidth*options.outHeight>20_000_000L)throw new IllegalArgumentException("图片过大或无效");
                    options.inJustDecodeBounds=false;options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>2880)options.inSampleSize=options.inSampleSize<1?2:options.inSampleSize*2;
                    try(var in=context.getContentResolver().openInputStream(uri)){bitmap=BitmapFactory.decodeStream(in,null,options);}
                }
                if(bitmap==null)throw new IllegalArgumentException("图片不可读");
                final Bitmap image=bitmap;var recognizer=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
                Executor cleanupExecutor=action->{try{worker.execute(action);}catch(java.util.concurrent.RejectedExecutionException stopped){action.run();}};
                recognizer.process(InputImage.fromBitmap(image,0)).addOnCompleteListener(cleanupExecutor,task->{
                    try{
                        if(epoch!=generation)return;
                        JSONObject doc=task.isSuccessful()?RuleOcrDocument.from(task.getResult(),image.getWidth(),image.getHeight()):new JSONObject().put("width",image.getWidth()).put("height",image.getHeight()).put("lines",new org.json.JSONArray());
                        double scale=Math.min(1,1440.0/Math.max(image.getWidth(),image.getHeight()));
                        Bitmap preview=Bitmap.createScaledBitmap(image,Math.max(1,(int)(image.getWidth()*scale)),Math.max(1,(int)(image.getHeight()*scale)),true);
                        ByteArrayOutputStream bytes=new ByteArrayOutputStream();preview.compress(Bitmap.CompressFormat.JPEG,85,bytes);if(preview!=image)preview.recycle();
                        String digest=PaymentListener.digest(Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP));
                        synchronized(this){if(epoch!=generation)return;id=request;document=doc;cloudImage=DeepSeekFallback.image(image);imageKey=digest;}
                        dispatch.accept("rule-image",new JSONObject(doc.toString()).put("suggestion",PaymentFieldLocator.suggest(doc)).put("sampleId",request).put("imageKey",digest).put("image","data:image/jpeg;base64,"+Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP)));
                    }catch(Exception e){sendError(dispatch,request,e.getMessage());}finally{image.recycle();recognizer.close();}
                });
            }catch(Exception e){if(bitmap!=null)bitmap.recycle();sendError(dispatch,request,"无法读取截图："+e.getMessage());}
        });
    }
    private static void sendError(BiConsumer<String,JSONObject> dispatch,String request,String text){try{dispatch.accept("rule-image-error",new JSONObject().put("sampleId",request).put("message",text));}catch(Exception ignored){}}
    synchronized JSONObject test(Context context,String request,JSONObject rule) throws Exception {
        if(document==null||!id.equals(request))throw new IllegalArgumentException("截图已失效，请重新选择");
        JSONObject learned=PaymentFieldLocator.learn(rule,document);
        JSONObject candidate=CustomPaymentRule.evaluate(learned,document,System.currentTimeMillis(),"screenshot-preview",CustomRuleRegistry.zone(context));
        String hash=CustomRuleRegistry.recordTest(context,learned);
        return new JSONObject().put("ok",true).put("candidate",candidate).put("rule",learned).put("testedHash",hash);
    }
    synchronized boolean ai(Context context,String request,String token,JSONObject rule,BiConsumer<String,JSONObject> dispatch)throws Exception {
        if(document==null||cloudImage==null||!id.equals(request))throw new IllegalArgumentException("截图已失效，请重新选择");
        CustomPaymentRule.validate(rule);
        JSONObject local=DeepSeekReceipt.local(document,rule.getString("origin"),System.currentTimeMillis(),CustomRuleRegistry.zone(context));
        if(local!=null){dispatch.accept("rule-image-local",new JSONObject().put("sampleId",request).put("token",token).put("candidate",local));return true;}
        try{JSONObject learned=PaymentFieldLocator.learn(rule,document);CustomPaymentRule.evaluate(learned,document,System.currentTimeMillis(),"screenshot-preview",CustomRuleRegistry.zone(context));return false;}
        catch(IllegalArgumentException|org.json.JSONException localFailure){/* Only unresolved local results may leave the device. */}
        final int epoch=generation;String origin=rule.getString("origin");
        return DeepSeekFallback.request(context,cloudImage,document,origin,imageKey,true,false,()->epoch==generation,
            candidate->{try{dispatch.accept("rule-image-ai",new JSONObject().put("sampleId",request).put("token",token).put("candidate",candidate));}catch(Exception ignored){}},
            message->{try{dispatch.accept("rule-image-ai-error",new JSONObject().put("sampleId",request).put("token",token).put("message",message));}catch(Exception ignored){}});
    }
}

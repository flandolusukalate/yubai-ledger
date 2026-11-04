package cn.yubai.ledger;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Deliberately plain test fixture, excluded from the normal APK. No ledger or payment fields. */
public final class PageReadProbeActivity extends Activity {
    private WebView web;
    @Override public void onCreate(Bundle saved){super.onCreate(saved);show(getIntent().getStringExtra("scenario"));}
    @Override public void onNewIntent(android.content.Intent intent){super.onNewIntent(intent);setIntent(intent);show(intent.getStringExtra("scenario"));}
    private void show(String requested){
        if(web!=null){web.destroy();web=null;}
        String scenario="web".equals(requested)?"web":"wechat".equals(requested)?"wechat":"native";
        if("stop".equals(requested))ProbeDiagnostics.stop(this);else ProbeDiagnostics.arm(this,scenario);
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(32,100,32,40);box.setBackgroundColor(Color.WHITE);
        TextView title=new TextView(this);title.setText("余白 · 页面读取对照测试");title.setTextSize(24);title.setTextColor(Color.BLACK);box.addView(title);
        TextView note=new TextView(this);note.setText("仅检查可读节点，最长 90 秒。只记录数量、可见性和固定标记匹配，不保存页面正文，不记账。\n当前模式："+("stop".equals(requested)?"已停止":scenario));note.setTextSize(16);note.setTextColor(Color.BLACK);box.addView(note);
        for(String[] action:new String[][]{{"原生测试页","native"},{"WebView 测试页","web"},{"准备检查微信页面","wechat"},{"停止诊断","stop"}}){
            Button button=new Button(this);button.setText(action[0]);button.setOnClickListener(v->show(action[1]));box.addView(button);
        }
        if(scenario.equals("native")){
            TextView marker=new TextView(this);marker.setText(ProbeDiagnostics.MARKER);marker.setTextSize(19);marker.setTextColor(Color.BLACK);box.addView(marker);
            TextView label=new TextView(this);label.setText("这是固定的原生 TextView 测试文本。");label.setTextColor(Color.BLACK);box.addView(label);
        }else if(scenario.equals("web")){
            web=new WebView(this);web.getSettings().setJavaScriptEnabled(false);web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);
            web.loadDataWithBaseURL(null,"<!doctype html><html lang='zh-CN'><meta name='viewport' content='width=device-width,initial-scale=1'><body><h2>本地 WebView 测试</h2><p>"+ProbeDiagnostics.MARKER+"</p><p>这段文字不来自任何真实账单。</p></body></html>","text/html","UTF-8",null);
            box.addView(web,new LinearLayout.LayoutParams(-1,400));
        }
        setContentView(box);
    }
    @Override protected void onDestroy(){if(web!=null)web.destroy();super.onDestroy();}
}

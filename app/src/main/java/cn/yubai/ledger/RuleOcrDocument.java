package cn.yubai.ledger;

import android.graphics.Rect;
import com.google.mlkit.vision.text.Text;
import org.json.JSONArray;
import org.json.JSONObject;

/** In-memory OCR geometry. Never put this document in the payment queue or backup. */
final class RuleOcrDocument {
    static JSONObject from(Text text,int width,int height) throws Exception {
        JSONArray lines=new JSONArray();int chars=0;
        for(Text.TextBlock block:text.getTextBlocks())for(Text.Line line:block.getLines()){
            Rect r=line.getBoundingBox();if(r==null||r.isEmpty())continue;
            String value=java.text.Normalizer.normalize(line.getText(),java.text.Normalizer.Form.NFKC).trim();
            chars+=value.length();if(lines.length()>=200||chars>8192)throw new IllegalArgumentException("页面文字过多，请选择完整付款页");
            int left=Math.max(0,r.left),top=Math.max(0,r.top),right=Math.min(width,r.right),bottom=Math.min(height,r.bottom);
            if(right<=left||bottom<=top)continue;
            lines.put(new JSONObject().put("text",value).put("x",(double)left/width).put("y",(double)top/height)
                .put("w",(double)(right-left)/width).put("h",(double)(bottom-top)/height));
        }
        return new JSONObject().put("width",width).put("height",height).put("lines",lines);
    }
}

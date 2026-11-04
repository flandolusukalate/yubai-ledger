package cn.yubai.ledger;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Diagnostic-only window selection comparison. No window titles or page text are saved. */
final class ProbeWindowDiagnostics {
    @SuppressWarnings("deprecation")
    static AccessibilityNodeInfo select(AccessibilityService service,AccessibilityNodeInfo original){
        JSONArray rows=new JSONArray();
        AccessibilityNodeInfo selected=original;
        int originalId=original==null?-1:original.getWindowId();
        try{
            List<AccessibilityWindowInfo> windows=service.getWindows();
            for(AccessibilityWindowInfo window:windows){
                AccessibilityNodeInfo node=null;
                try{
                    node=window.getRoot();
                    if(node==null||!ProbeDiagnostics.accepts(service,node.getPackageName()))continue;
                    JSONObject row=new JSONObject().put("id",window.getId()).put("type",window.getType())
                        .put("active",window.isActive()).put("focused",window.isFocused())
                        .put("layer",window.getLayer()).put("children",node.getChildCount())
                        .put("visible",node.isVisibleToUser()).put("sameAsOriginal",originalId==window.getId());
                    rows.put(row);
                    if(window.getType()==AccessibilityWindowInfo.TYPE_APPLICATION && window.isFocused()
                        && node.isVisibleToUser() && node.getChildCount()>0
                        && (selected==null||!selected.isVisibleToUser()||selected.getChildCount()==0)){
                        if(selected!=null)selected.recycle();
                        selected=node;node=null;
                    }
                }finally{if(node!=null)node.recycle();window.recycle();}
            }
            ProbeDiagnostics.windows(rows,originalId,selected==null?-1:selected.getWindowId());
        }catch(Exception e){ProbeDiagnostics.windowsError(e.getClass().getSimpleName());}
        return selected;
    }
}

package cn.yubai.ledger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.time.Instant;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Temporary diagnostic build only. Stores counts and fixed-fixture matches, never page text. */
final class ProbeDiagnostics {
    static final String MARKER="YUBAI_NODE_PROBE_314159";
    private static JSONObject root=new JSONObject();
    private static JSONObject windows=new JSONObject();
    private static Boolean cacheCleared;
    private static long started;
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("page_read_probe",0);}
    static void arm(Context c,String scenario){
        prefs(c).edit().putString("scenario",scenario).putLong("until",System.currentTimeMillis()+90_000)
            .putString("rows","[]").apply();
    }
    static void stop(Context c){prefs(c).edit().putLong("until",0).apply();}
    static boolean armed(Context c){long left=prefs(c).getLong("until",0)-System.currentTimeMillis();return left>0&&left<=90_000;}
    static boolean accepts(Context c,CharSequence pkg){
        if(pkg==null||!armed(c))return false;
        String scenario=prefs(c).getString("scenario","");
        return (scenario.equals("wechat")?"com.tencent.mm":c.getPackageName()).contentEquals(pkg);
    }
    static boolean acceptsEvent(Context c,AccessibilityEvent event){return event!=null&&accepts(c,event.getPackageName());}
    static void begin(){root=new JSONObject();windows=new JSONObject();cacheCleared=null;started=SystemClock.elapsedRealtime();}
    static void cache(boolean cleared){cacheCleared=cleared;}
    static void windows(JSONArray rows,int originalId,int selectedId){
        try{windows.put("matchingWindows",rows).put("originalId",originalId).put("selectedId",selectedId);}catch(Exception ignored){}
    }
    static void windowsError(String error){try{windows.put("error",error);}catch(Exception ignored){}}
    static void inspectRoot(Context c,AccessibilityNodeInfo node){
        try{
            String name=String.valueOf(node.getClassName());
            root.put("package",String.valueOf(node.getPackageName())).put("class",name.matches("[A-Za-z0-9_.$]{1,160}")?name:"nonstandard")
                .put("children",node.getChildCount()).put("visible",node.isVisibleToUser())
                .put("sensitive",Build.VERSION.SDK_INT>=34&&node.isAccessibilityDataSensitive());
        }catch(Exception ignored){}
    }
    static synchronized void record(Context c,String state,List<String> lines){
        if(!armed(c))return;
        try{
            SharedPreferences p=prefs(c),collector=c.getSharedPreferences("collector",0);
            JSONArray rows=new JSONArray(p.getString("rows","[]"));
            JSONObject row=new JSONObject().put("at",Instant.now().toString()).put("scenario",p.getString("scenario",""))
                .put("state",state).put("root",root).put("windows",windows).put("elapsedMs",SystemClock.elapsedRealtime()-started)
                .put("cacheCleared",cacheCleared==null?JSONObject.NULL:cacheCleared)
                .put("nodeCount",collector.getInt("page_node_count",0)).put("visibleTextCount",collector.getInt("page_text_count",0))
                .put("fixtureMarkerSeen",lines!=null&&lines.stream().anyMatch(MARKER::equals));
            JSONArray bounded=new JSONArray();for(int i=Math.max(0,rows.length()-23);i<rows.length();i++)bounded.put(rows.get(i));
            bounded.put(row);p.edit().putString("rows",bounded.toString()).apply();
        }catch(Exception ignored){}
    }
}

package cn.yubai.ledger;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;

/** Atomic snapshot and durable collector queue share an app-private SQLite database. */
public final class Store extends SQLiteOpenHelper {
    public Store(Context context) { super(context, "yubai.db", null, 1); setWriteAheadLoggingEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE ledger(id INTEGER PRIMARY KEY CHECK(id=1), body TEXT NOT NULL)");
        db.execSQL("CREATE TABLE inbox(id INTEGER PRIMARY KEY AUTOINCREMENT, fingerprint TEXT UNIQUE NOT NULL, body TEXT NOT NULL)");
        db.execSQL("CREATE TABLE recovery(id INTEGER PRIMARY KEY CHECK(id=1), body TEXT NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldV,int newV) { throw new IllegalStateException("Unsupported schema migration"); }
    public synchronized String load() {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT body FROM ledger WHERE id=1",null)) { return c.moveToFirst()?c.getString(0):""; }
    }
    public synchronized void save(String body) throws Exception {
        JSONObject value=new JSONObject(body);
        if(value.getInt("version")!=1 || body.length()>20_000_000)throw new IllegalArgumentException("Invalid snapshot");
        ContentValues row=new ContentValues();row.put("id",1);row.put("body",body);
        if(getWritableDatabase().insertWithOnConflict("ledger",null,row,SQLiteDatabase.CONFLICT_REPLACE)<0)throw new IllegalStateException("保存失败");
    }
    private static long revision(String body) throws Exception {
        if(body.isEmpty())return 0;
        JSONObject value=new JSONObject(body);Object raw=value.opt("revision");
        if(raw==null)throw new IllegalArgumentException("Missing revision");
        if(!(raw instanceof Integer)&&!(raw instanceof Long))throw new IllegalArgumentException("Invalid revision");
        long result=((Number)raw).longValue();if(result<0||result>9_007_199_254_740_991L)throw new IllegalArgumentException("Invalid revision");return result;
    }
    private static void expectedRevision(long expected){if(expected<0||expected>=9_007_199_254_740_991L)throw new IllegalArgumentException("Invalid expected revision");}
    /** Cross-instance compare-and-swap. Snapshot and inbox acknowledgement commit together. */
    public synchronized boolean commit(long expected,String body,String queueIds) throws Exception {
        expectedRevision(expected);
        if(revision(body)!=expected+1)throw new IllegalArgumentException("Snapshot revision must advance once");
        JSONArray ids=new JSONArray(queueIds);
        for(int i=0;i<ids.length();i++)if(!(ids.get(i) instanceof Integer)&&!(ids.get(i) instanceof Long)||ids.getLong(i)<=0)throw new IllegalArgumentException("Invalid queue id");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            if(revision(load())!=expected)return false;
            save(body);
            for(int i=0;i<ids.length();i++)db.delete("inbox","id=?",new String[]{String.valueOf(ids.getLong(i))});
            db.setTransactionSuccessful();return true;
        }finally{db.endTransaction();}
    }
    /** Restore is also conditional and advances the live revision, preventing stale UI writes. */
    public synchronized boolean replaceCAS(long expected,String body) throws Exception {
        expectedRevision(expected);JSONObject value=new JSONObject(body);value.put("revision",expected+1);
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            String old=load();if(revision(old)!=expected)return false;
            if(!old.isEmpty()){ContentValues row=new ContentValues();row.put("id",1);row.put("body",old);db.insertWithOnConflict("recovery",null,row,SQLiteDatabase.CONFLICT_REPLACE);}
            save(value.toString());db.setTransactionSuccessful();return true;
        }finally{db.endTransaction();}
    }
    /** A damaged snapshot has no usable revision; compare its exact bytes instead. */
    public synchronized boolean replaceCorrupt(String expectedBody,String body) throws Exception {
        JSONObject value=new JSONObject(body);value.put("revision",Math.max(1,Math.min(9_007_199_254_740_990L,System.currentTimeMillis())));
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            String old=load();if(!old.equals(expectedBody))return false;
            boolean invalid=false;try{revision(old);}catch(Exception error){invalid=true;}
            if(!invalid)throw new IllegalArgumentException("Snapshot has a usable revision");
            ContentValues row=new ContentValues();row.put("id",1);row.put("body",old);db.insertWithOnConflict("recovery",null,row,SQLiteDatabase.CONFLICT_REPLACE);
            save(value.toString());db.setTransactionSuccessful();return true;
        }finally{db.endTransaction();}
    }
    public synchronized void enqueue(JSONObject item) throws Exception {
        ContentValues row=new ContentValues();row.put("fingerprint",item.getString("fingerprint"));row.put("body",item.toString());
        getWritableDatabase().insertWithOnConflict("inbox",null,row,SQLiteDatabase.CONFLICT_IGNORE);
    }
    public synchronized void replace(String body) throws Exception {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {String old=load();if(!old.isEmpty()){ContentValues row=new ContentValues();row.put("id",1);row.put("body",old);db.insertWithOnConflict("recovery",null,row,SQLiteDatabase.CONFLICT_REPLACE);}save(body);db.setTransactionSuccessful();}finally{db.endTransaction();}
    }
    public synchronized String recovery() {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT body FROM recovery WHERE id=1",null)){return c.moveToFirst()?c.getString(0):"";}
    }
    public synchronized String inbox() throws Exception {
        JSONArray result=new JSONArray();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,body FROM inbox ORDER BY id LIMIT 500",null)) {
            while(c.moveToNext()){JSONObject o=new JSONObject(c.getString(1));o.put("queueId",c.getLong(0));result.put(o);}
        }return result.toString();
    }
    public synchronized void acknowledge(String json) throws Exception {
        JSONArray ids=new JSONArray(json);SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {for(int i=0;i<ids.length();i++)db.delete("inbox","id=?",new String[]{String.valueOf(ids.getLong(i))});db.setTransactionSuccessful();}finally{db.endTransaction();}
    }
}

package cn.yubai.ledger;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Device-only opt-in and encrypted personal credential. Never part of the ledger/backup or JS reads. */
final class DeepSeekSettings {
    private static final String ALIAS="yubai.deepseek.key.v1";
    static SharedPreferences prefs(Context c){return c.getSharedPreferences("deepseek_private",0);}
    private static SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(ALIAS))return (SecretKey)store.getKey(ALIAS,null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    static synchronized void configure(Context c,boolean enabled,String secret,boolean forget,boolean consent)throws Exception {
        SharedPreferences p=prefs(c);String value=secret==null?"":secret.trim();
        if(enabled&&!consent)throw new IllegalArgumentException("请确认付款图片将发送至 DeepSeek");
        if(forget&&enabled)throw new IllegalArgumentException("清除密钥时请先关闭云端识别");
        if(!value.isEmpty()&&!value.matches("[A-Za-z0-9_-]{16,256}"))throw new IllegalArgumentException("API Key 格式无效");
        if(enabled&&value.isEmpty()&&p.getString("secret","").isEmpty())throw new IllegalArgumentException("请先填写自己的 DeepSeek API Key");
        SharedPreferences.Editor edit=p.edit().putBoolean("enabled",enabled).putLong("revision",p.getLong("revision",0)+1)
            .putString("state","idle").putString("message",enabled?"已启用；仅本地失败时联网，明确的窗口付款自动查重记录":"云端识别已关闭，本地功能照常可用");
        if(forget)edit.remove("secret").remove("iv");
        else if(!value.isEmpty()){
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
            edit.putString("secret",Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP))
                .putString("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP));
        }
        if(!edit.commit())throw new IllegalStateException("设置保存失败");
    }
    static String secret(Context c)throws Exception {
        SharedPreferences p=prefs(c);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(p.getString("iv",""),Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(p.getString("secret",""),Base64.NO_WRAP)),StandardCharsets.UTF_8);
    }
    static boolean enabled(Context c){return prefs(c).getBoolean("enabled",false)&&!prefs(c).getString("secret","").isEmpty();}
    static long revision(Context c){return prefs(c).getLong("revision",0);}
    static JSONObject status(Context c)throws Exception {
        SharedPreferences p=prefs(c);return new JSONObject().put("enabled",enabled(c)).put("configured",!p.getString("secret","").isEmpty())
            .put("model",DeepSeekFallback.MODEL).put("state",p.getString("state","idle")).put("message",p.getString("message","尚未调用云端"))
            .put("lastMs",p.getLong("lastMs",0)).put("lastAt",p.getString("lastAt",""));
    }
}

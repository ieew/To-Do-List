package com.dshkit.core;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 设备凭证的本地加密（Android Keystore AES/GCM）。
 *
 * 凭证文件落在用户可见的 `dsh.d/` 目录，不能存明文；
 * 密钥在 Keystore 里，换机/清数据后自然失效，届时重新授权即可。
 * Keystore 不可用时退化为明文（仍可工作，但会在日志里提示）。
 */
public final class Secret {

    private static final String ALIAS = "dsh-kit-device-credential";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String PREFIX = "enc:";

    private Secret() {
    }

    public static String seal(String plain) {
        if (plain == null || plain.isEmpty()) return plain;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] body = cipher.doFinal(plain.getBytes("UTF-8"));
            String iv = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP);
            String data = Base64.encodeToString(body, Base64.NO_WRAP);
            return PREFIX + iv + ":" + data;
        } catch (Throwable t) {
            return plain;
        }
    }

    public static String open(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) return stored;
        try {
            String[] parts = stored.substring(PREFIX.length()).split(":", 2);
            if (parts.length != 2) return null;
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(),
                    new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            byte[] body = cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP));
            return new String(body, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            return (SecretKey) ks.getKey(ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}

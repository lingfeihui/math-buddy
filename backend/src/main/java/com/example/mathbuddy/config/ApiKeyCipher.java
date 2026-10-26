package com.example.mathbuddy.config;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.Console;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * API Key 加密/解密工具。
 * <p>
 * 采用 AES-256-GCM，主密钥通过 SHA-256 派生为 32 字节密钥。
 * 加密结果格式：ENC:base64(iv(12字节) + ciphertext + authTag(16字节))。
 * <p>
 * 如果待解密值不是以 ENC: 开头，则原样返回，方便直接使用明文环境变量。
 */
public class ApiKeyCipher {

    private static final String PREFIX = "ENC:";
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 128;

    /** 解密：ENC: 开头则解密，否则原样返回 */
    public static String decrypt(String encrypted, String masterKey) {
        if (encrypted == null || encrypted.isBlank()) {
            return encrypted;
        }
        if (!encrypted.startsWith(PREFIX)) {
            return encrypted;
        }
        if (masterKey == null || masterKey.isBlank()) {
            throw new IllegalArgumentException(
                    "检测到加密的 API Key，但未配置解密主密钥。请设置环境变量 QWEN_API_KEY_MASTER 或在配置中提供 qwen.api-key-master。");
        }
        try {
            SecretKey key = deriveKey(masterKey);
            byte[] combined = Base64.getDecoder().decode(encrypted.substring(PREFIX.length()).trim());
            if (combined.length < IV_LENGTH) {
                throw new IllegalArgumentException("加密数据格式不正确");
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            byte[] cipherText = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, cipherText, 0, cipherText.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("API Key 解密失败，请检查主密钥是否正确", e);
        }
    }

    /** 加密 */
    public static String encrypt(String plain, String masterKey) {
        if (plain == null || plain.isBlank() || masterKey == null || masterKey.isBlank()) {
            throw new IllegalArgumentException("API Key 和主密钥都不能为空");
        }
        try {
            SecretKey key = deriveKey(masterKey);
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("API Key 加密失败", e);
        }
    }

    private static SecretKey deriveKey(String masterKey) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] keyBytes = digest.digest(masterKey.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(keyBytes, ALGORITHM);
    }

    /** 命令行工具：读取 API Key 和主密钥，输出加密后的字符串 */
    public static void main(String[] args) {
        Console console = System.console();
        if (console == null) {
            System.err.println("请在命令行环境下运行此工具（IDE 内运行无法安全读取密码）。");
            System.exit(1);
        }
        char[] plainChars = console.readPassword("请输入要加密的 API Key: ");
        char[] masterChars = console.readPassword("请输入主密钥（部署时设置到 QWEN_API_KEY_MASTER）: ");
        try {
            String encrypted = encrypt(new String(plainChars), new String(masterChars));
            System.out.println("加密结果（以 ENC: 开头）：");
            System.out.println(encrypted);
        } finally {
            java.util.Arrays.fill(plainChars, ' ');
            java.util.Arrays.fill(masterChars, ' ');
        }
    }
}

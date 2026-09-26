package com.travelmind.aiagent.service;

import cn.hutool.crypto.digest.DigestUtil;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 用户密码哈希服务。
 *
 * 新密码统一使用 PBKDF2-HMAC-SHA256 + 随机盐；旧版 MD5 只用于一次性兼容，
 * 用户成功登录后 UserService 会立即把旧哈希升级为 PBKDF2。
 */
@Component
public class PasswordHashService {

    private static final String PREFIX = "pbkdf2";
    private static final String LEGACY_SALT = "travelmind";
    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public String hash(String rawPassword) {
        byte[] salt = new byte[SALT_BYTES];
        SECURE_RANDOM.nextBytes(salt);
        byte[] encoded = derive(rawPassword, salt, ITERATIONS);
        return PREFIX + "$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(encoded);
    }

    public boolean matches(String rawPassword, String storedHash) {
        if (rawPassword == null || storedHash == null || storedHash.isBlank()) return false;
        if (!storedHash.startsWith(PREFIX + "$")) {
            byte[] expected = storedHash.getBytes(StandardCharsets.UTF_8);
            byte[] actual = DigestUtil.md5Hex(LEGACY_SALT + rawPassword).getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(expected, actual);
        }
        try {
            String[] parts = storedHash.split("\\$", -1);
            if (parts.length != 4) return false;
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = derive(rawPassword, salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException malformedHash) {
            return false;
        }
    }

    public boolean needsUpgrade(String storedHash) {
        return storedHash == null || !storedHash.startsWith(PREFIX + "$");
    }

    private byte[] derive(String rawPassword, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(rawPassword.toCharArray(), salt, iterations, HASH_BYTES * 8);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception error) {
            throw new IllegalStateException("Password hashing is unavailable", error);
        } finally {
            spec.clearPassword();
        }
    }
}

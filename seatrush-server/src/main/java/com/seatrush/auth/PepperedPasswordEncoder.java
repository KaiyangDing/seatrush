package com.seatrush.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * BCrypt with a per-user salt (stored inside the hash) plus a server-side pepper.
 * The password is first run through HMAC-SHA256 keyed with the pepper, so the pepper
 * never reaches the database and long passwords stay within BCrypt's 72-byte limit.
 */
public class PepperedPasswordEncoder implements PasswordEncoder {

    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private final SecretKeySpec pepperKey;

    public PepperedPasswordEncoder(String pepper) {
        this.pepperKey = new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return bcrypt.encode(applyPepper(rawPassword));
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return bcrypt.matches(applyPepper(rawPassword), encodedPassword);
    }

    private String applyPepper(CharSequence rawPassword) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(pepperKey);
            byte[] digest = mac.doFinal(rawPassword.toString().getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
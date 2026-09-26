package com.travelmind.aiagent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordHashServiceTest {

    private final PasswordHashService service = new PasswordHashService();

    @Test
    void hashesAndVerifiesNewPasswords() {
        String encoded = service.hash("a-secure-password");

        assertTrue(encoded.startsWith("pbkdf2$210000$"));
        assertTrue(service.matches("a-secure-password", encoded));
        assertFalse(service.matches("wrong-password", encoded));
        assertFalse(service.needsUpgrade(encoded));
    }

    @Test
    void verifiesSeededAdminPassword() {
        String seeded = "pbkdf2$210000$m35MXrdYG0aCabRO1SUlTw==$3CepHpP9evuVAEnHaZOhnUCP5bVaXORt6TRSUn0dc40=";

        assertTrue(service.matches("123456", seeded));
        assertFalse(service.matches("123457", seeded));
    }

    @Test
    void acceptsLegacyMd5AndMarksItForUpgrade() {
        String legacy = "abcb55ab80d49d3ba8c638e055caff1f";

        assertTrue(service.matches("123456", legacy));
        assertTrue(service.needsUpgrade(legacy));
    }
}

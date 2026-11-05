package com.arthur.jdragresume.security;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtPropertiesTests {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void rejectsJwtSecretShorterThan32Bytes() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("too-short-secret");

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void acceptsJwtSecretAtLeast32Bytes() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("0123456789abcdef0123456789abcdef");
        properties.setExpirationMinutes(15);
        properties.setRefreshExpirationDays(7);

        assertTrue(validator.validate(properties).isEmpty());
    }
}

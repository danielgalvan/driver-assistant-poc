package com.driverassistant.diagnostics;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class DiagnosticTextSanitizerTest {
    @Test
    public void preservesOfferValuesAndUnitsWhileRedactingPersonalData() {
        String safe = DiagnosticTextSanitizer.sanitizeRelevant(
                "R$ 29,09 · 7 min (0,8 km) · Rua Francisco Torres, 123, Curitiba · Passageiro: Maria Silva");

        assertTrue(safe.contains("R$ 29,09"));
        assertTrue(safe.contains("7 min"));
        assertTrue(safe.contains("0,8 km"));
        assertTrue(safe.contains("[ENDERECO]"));
        assertTrue(safe.contains("[PESSOA]"));
        assertFalse(safe.contains("Francisco"));
        assertFalse(safe.contains("Maria"));
    }

    @Test
    public void omitsIrrelevantTextAndRedactsPhoneAndEmail() {
        assertTrue(DiagnosticTextSanitizer.sanitizeRelevant("Maria Silva").isEmpty());
        String safe = DiagnosticTextSanitizer.sanitizeRelevant("R$ 15,00 · (41) 99999-1234 · a@b.com");
        assertTrue(safe.contains("R$ 15,00"));
        assertFalse(safe.contains("99999"));
        assertFalse(safe.contains("a@b.com"));
    }

    @Test
    public void redactsPassengerNamesBeforeRatingsAndWhitelistsCategoryFields() {
        String safe = DiagnosticTextSanitizer.sanitizeRelevant("Ana Clara · 4,95 ★");
        assertFalse(safe.contains("Ana"));
        assertTrue(safe.contains("4,95"));
        assertEquals("UberX", DiagnosticTextSanitizer.sanitizeCategory("Uber X"));
        assertNull(DiagnosticTextSanitizer.sanitizeCategory("Maria Silva"));
    }
}

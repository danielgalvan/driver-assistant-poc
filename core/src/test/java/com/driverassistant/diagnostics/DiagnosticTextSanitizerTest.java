package com.driverassistant.diagnostics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    @Test
    public void preservesFragmentedValuesOnlyWhenNearbyContextIdentifiesTheirMeaning() {
        assertEquals("4,95", DiagnosticTextSanitizer.sanitizeRelevantFragment("4,95", "Nota passageiro"));
        assertEquals("20,03", DiagnosticTextSanitizer.sanitizeRelevantFragment("20,03", "R$ valor"));
        assertEquals("1.234,56", DiagnosticTextSanitizer.sanitizeRelevantFragment("1.234,56", "R$ fare"));
        assertEquals("Uber", DiagnosticTextSanitizer.sanitizeRelevantFragment("Uber", "UberX categoria"));
        assertTrue(DiagnosticTextSanitizer.sanitizeRelevantFragment("20,03", "mapa Curitiba").isEmpty());
    }

    @Test public void keepsOfferMetricsAndRedactsPassengerNameAndAddress() {
        String result = DiagnosticTextSanitizer.sanitizeRelevant(
                "Daniel 4,93 · UberX · R$ 29,09 · 7 min (0,8 km) · Rua Francisco Torres, 123, Centro");

        assertTrue(result.contains("UberX"));
        assertTrue(result.contains("R$ 29,09"));
        assertTrue(result.contains("7 min"));
        assertTrue(result.contains("4,93"));
        assertFalse(result.contains("Daniel"));
        assertFalse(result.contains("Francisco"));
        assertFalse(result.contains("123"));
    }

    @Test public void retainsOfferBrowseLabelAndSmallBadgeCountFromRelevantResourceContext() {
        assertEquals("Oportunidades", DiagnosticTextSanitizer.sanitizeRelevantFragment("Oportunidades", ""));
        assertEquals("2", DiagnosticTextSanitizer.sanitizeRelevantFragment("2",
                "com.ubercab.driver:id/ub__driver_job_offers_pill_badge"));
    }
}

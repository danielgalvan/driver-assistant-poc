package com.driverassistant.parser;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class LocalizedNumberParserTest {
    @Test public void parsesBrazilianCurrencySeparators() {
        assertEquals(1234.56, LocalizedNumberParser.parse("R$ 1.234,56"), 0.0001);
        assertEquals(12.50, LocalizedNumberParser.parse("12,50"), 0.0001);
    }

    @Test public void parsesDotDecimalAndGroupedFormats() {
        assertEquals(1234.56, LocalizedNumberParser.parse("1,234.56"), 0.0001);
        assertEquals(1234.0, LocalizedNumberParser.parse("1.234"), 0.0001);
        assertEquals(12.45, LocalizedNumberParser.parse("12.45"), 0.0001);
    }

    @Test public void invalidValuesRemainUnavailable() {
        assertNull(LocalizedNumberParser.parse(null));
        assertNull(LocalizedNumberParser.parse("R$"));
        assertNull(LocalizedNumberParser.parse("12,3,4"));
    }
}

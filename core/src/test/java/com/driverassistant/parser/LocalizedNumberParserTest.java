package com.driverassistant.parser;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class LocalizedNumberParserTest {
    @Test public void parsesBrazilianAndInternationalMoneyFormats() {
        assertEquals(1234.56, LocalizedNumberParser.parse("R$ 1.234,56", LocalizedNumberParser.Context.MONEY), 0.0001);
        assertEquals(1234.56, LocalizedNumberParser.parse("1,234.56", LocalizedNumberParser.Context.MONEY), 0.0001);
        assertEquals(1250.0, LocalizedNumberParser.parse("1.250", LocalizedNumberParser.Context.MONEY), 0.0001);
        assertEquals(12.50, LocalizedNumberParser.parse("12,50", LocalizedNumberParser.Context.MONEY), 0.0001);
    }

    @Test public void parsesDistanceWithThreeDecimalPlacesAsDecimal() {
        assertEquals(0.75, LocalizedNumberParser.parse("0,750", LocalizedNumberParser.Context.DISTANCE), 0.0001);
        assertEquals(0.75, LocalizedNumberParser.parse("0.750", LocalizedNumberParser.Context.DISTANCE), 0.0001);
        assertEquals(1.25, LocalizedNumberParser.parse("1.250", LocalizedNumberParser.Context.DISTANCE), 0.0001);
    }

    @Test public void genericParsingRejectsAmbiguousSingleSeparatorTriplets() {
        assertNull(LocalizedNumberParser.parse("1.234"));
        assertNull(LocalizedNumberParser.parse("1,234"));
        assertNull(LocalizedNumberParser.parse("12.345"));
        assertEquals(1234.56, LocalizedNumberParser.parse("1.234,56"), 0.0001);
        assertEquals(1234.56, LocalizedNumberParser.parse("1,234.56"), 0.0001);
    }

    @Test public void rejectsInvalidValuesAndOutOfRangeRatings() {
        assertNull(LocalizedNumberParser.parse(null));
        assertNull(LocalizedNumberParser.parse("R$", LocalizedNumberParser.Context.MONEY));
        assertNull(LocalizedNumberParser.parse("12,3,4", LocalizedNumberParser.Context.MONEY));
        assertNull(LocalizedNumberParser.parse("1,234", LocalizedNumberParser.Context.MONEY));
        assertNull(LocalizedNumberParser.parse("5,5", LocalizedNumberParser.Context.RATING));
        assertNull(LocalizedNumberParser.parse("Infinity", LocalizedNumberParser.Context.MONEY));
    }
}

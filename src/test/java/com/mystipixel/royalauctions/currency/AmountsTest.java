package com.mystipixel.royalauctions.currency;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

// Tax and price precision per currency scale.
class AmountsTest {

    @Test void taxRoundsUpToTheScaleAndNeverExceedsThePrice() {
        assertEquals(2, Amounts.tax(15, 0.1, 0));
        assertEquals(10, Amounts.tax(100, 0.1, 0), "0.1 is not stretched into 10.000000000000002");
        assertEquals(1, Amounts.tax(1, 0.1, 0), "any taxed sale keeps at least one whole unit");
        assertEquals(1.56, Amounts.tax(15.55, 0.1, 2));
        assertEquals(0.01, Amounts.tax(0.05, 0.1, 2));
        assertEquals(0.3, Amounts.tax(3, 0.1, 2));
        assertEquals(0, Amounts.tax(15, 0, 0));
        assertEquals(15, Amounts.tax(15, 1, 0));
    }

    @Test void taxOnAnUnroundedCurrencyIsExact() {
        assertEquals(7, Amounts.tax(100, 0.07, Amounts.UNROUNDED), "not 7.000000000000001");
        assertEquals(1.555, Amounts.tax(15.55, 0.1, Amounts.UNROUNDED));
        assertEquals(0.0001, Amounts.tax(10, 0.00001, Amounts.UNROUNDED));
        assertEquals(15.55, Amounts.tax(15.55, 1, Amounts.UNROUNDED));
    }

    @Test void netIsExactInDecimal() {
        assertEquals(13.99, Amounts.net(15.55, 1.56));
        assertEquals(0.7, Amounts.net(1, 0.3));
    }

    @Test void fitsChecksTheDigitsAfterThePoint() {
        assertTrue(Amounts.fits(100, 0));
        assertTrue(Amounts.fits(1e9, 0));
        assertFalse(Amounts.fits(10.5, 0));
        assertTrue(Amounts.fits(10.5, 1));
        assertFalse(Amounts.fits(1.234, 2));
        assertFalse(Amounts.fits(Double.NaN, 2));
    }

    @Test void anUnroundedCurrencyKeepsEveryDigit() {
        assertTrue(Amounts.fits(1.001, Amounts.UNROUNDED));
        assertTrue(Amounts.fits(0.123456789, Amounts.UNROUNDED));
        assertFalse(Amounts.fits(Double.POSITIVE_INFINITY, Amounts.UNROUNDED));
        assertEquals(2.469, Amounts.roundUp(2.469, Amounts.UNROUNDED));
        var unrounded = java.util.OptionalInt.of(Amounts.UNROUNDED);
        assertEquals(7, Amounts.share(100, 0.07, 0, unrounded), "no binary noise without rounding");
        assertEquals(0.707, Amounts.share(10.1, 0.07, 0, unrounded));
        assertEquals(5, Amounts.share(10.1, 0.07, 5, unrounded));
    }

    @Test void roundUpGoesToTheNextUnitOfTheScale() {
        assertEquals(62, Amounts.roundUp(61.7, 0));
        assertEquals(10, Amounts.roundUp(10, 0));
        assertEquals(2.47, Amounts.roundUp(2.469, 2));
    }
}

package com.mystipixel.royalauctions.currency;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

// Price precision per currency scale.
class AmountsTest {

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

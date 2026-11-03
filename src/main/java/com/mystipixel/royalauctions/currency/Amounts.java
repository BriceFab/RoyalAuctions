package com.mystipixel.royalauctions.currency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.OptionalInt;

/** Rounding of amounts to a currency's scale. BigDecimal.valueOf keeps 0.1 * 3 from becoming 0.30000000000000004. */
public final class Amounts {

    /** Scale of a currency that does not round (a Vault economy whose fractionalDigits() is -1). */
    public static final int UNROUNDED = -1;

    private Amounts() {
    }

    /** {@code amount} rounded up to {@code scale}; unchanged when the currency does not round. */
    public static double roundUp(double amount, int scale) {
        if (scale == UNROUNDED) {
            return amount;
        }
        return BigDecimal.valueOf(amount).setScale(scale, RoundingMode.CEILING).doubleValue();
    }

    /**
     * The larger of {@code floor} and {@code rate} of {@code amount}, never negative, rounded up to {@code scale}
     * once (left exact without a scale). In decimal: a double product turns 7% of 100 into 7.000000000000001.
     */
    public static double share(double amount, double rate, double floor, OptionalInt scale) {
        BigDecimal share = BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(rate))
                .max(BigDecimal.valueOf(floor)).max(BigDecimal.ZERO);
        return (scale.isPresent() ? rounded(share, scale.getAsInt()) : share).doubleValue();
    }

    /** Whether {@code amount} has no more than {@code scale} digits after the decimal point; any finite amount when the currency does not round. */
    public static boolean fits(double amount, int scale) {
        if (!Double.isFinite(amount)) {
            return false;
        }
        return scale == UNROUNDED || BigDecimal.valueOf(amount).stripTrailingZeros().scale() <= scale;
    }

    private static BigDecimal rounded(BigDecimal amount, int scale) {
        return scale == UNROUNDED ? amount : amount.setScale(scale, RoundingMode.CEILING);
    }
}

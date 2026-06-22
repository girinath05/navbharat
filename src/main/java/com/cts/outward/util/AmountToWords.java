package com.cts.outward.util;

import java.math.BigDecimal;

/**
 * Converts a BigDecimal amount to Indian number words.
 * Example: 500000.00 → "Five Lakh Rupees Only"
 *          2000000.50 → "Twenty Lakh And Fifty Paise Only"
 */
public class AmountToWords {

    private static final String[] ONES = {
        "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
        "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen",
        "Sixteen", "Seventeen", "Eighteen", "Nineteen"
    };

    private static final String[] TENS = {
        "", "", "Twenty", "Thirty", "Forty", "Fifty",
        "Sixty", "Seventy", "Eighty", "Ninety"
    };

    /**
     * Converts BigDecimal to Indian words.
     * Returns "Zero Rupees Only" for zero.
     * Returns null if input is null or negative.
     */
    public static String convert(BigDecimal amount) {
        if (amount == null) return null;
        if (amount.compareTo(BigDecimal.ZERO) < 0) return null;
        if (amount.compareTo(BigDecimal.ZERO) == 0) return "Zero Rupees Only";

        long rupees = amount.longValue();
        int paise = amount.remainder(BigDecimal.ONE)
                         .multiply(new BigDecimal(100))
                         .intValue();

        StringBuilder result = new StringBuilder();

        if (rupees > 0) {
            result.append(toIndianWords(rupees)).append(" Rupees");
        }

        if (paise > 0) {
            if (rupees > 0) result.append(" And ");
            result.append(toWords(paise)).append(" Paise");
        }

        result.append(" Only");
        return result.toString();
    }

    private static String toIndianWords(long n) {
        if (n == 0) return "";

        StringBuilder sb = new StringBuilder();

        // Crore
        long crore = n / 10_000_000L;
        if (crore > 0) {
            sb.append(toWords((int) crore)).append(" Crore ");
            n %= 10_000_000L;
        }

        // Lakh
        long lakh = n / 100_000L;
        if (lakh > 0) {
            sb.append(toWords((int) lakh)).append(" Lakh ");
            n %= 100_000L;
        }

        // Thousand
        long thousand = n / 1000L;
        if (thousand > 0) {
            sb.append(toWords((int) thousand)).append(" Thousand ");
            n %= 1000L;
        }

        // Hundred
        long hundred = n / 100L;
        if (hundred > 0) {
            sb.append(ONES[(int) hundred]).append(" Hundred ");
            n %= 100L;
        }

        if (n > 0) {
            sb.append(toWords((int) n));
        }

        return sb.toString().trim();
    }

    // Handles numbers 1-99
    private static String toWords(int n) {
        if (n < 20) return ONES[n];
        return (TENS[n / 10] + (n % 10 != 0 ? " " + ONES[n % 10] : "")).trim();
    }
}
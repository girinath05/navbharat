/*
 * ============================================================
 *  Project  : Navbharat CTS Outward
 *  File     : AmountToWords.java
 *  Package  : com.cts.outward.util
 *  Purpose  : Utility class that converts a BigDecimal currency
 *             amount into Indian-English words (Crore / Lakh /
 *             Thousand / Hundred) with Rupees and Paise suffix.
 *             Used for cheque amount printing and CTS reports.
 *  Author   : [Name]
 *  Date     : June 2026
 * ============================================================
 */

package com.cts.outward.util;

import java.math.BigDecimal;

/**
 * File    : AmountToWords.java
 * Package : com.cts.outward.util
 * Purpose : Converts a {@link BigDecimal} monetary amount to its
 *           Indian-English word representation following the Indian
 *           numbering system (Crore → Lakh → Thousand → Hundred).
 *
 * <p>Examples:
 * <pre>
 *   500000.00  → "Five Lakh Rupees Only"
 *   2000000.50 → "Twenty Lakh And Fifty Paise Only"
 *   0.00       → "Zero Rupees Only"
 * </pre>
 *
 * <p>This class is stateless; all methods are static. Not intended
 * to be instantiated.
 */
public class AmountToWords {

    /**
     * Word table for numbers 0–19 (covers both units and teens).
     * Index 0 is intentionally empty — zero is handled as a special case
     * at the {@link #convert(BigDecimal)} level.
     */
    private static final String[] ONES_AND_TEENS = {
        "",          "One",       "Two",       "Three",    "Four",
        "Five",      "Six",       "Seven",     "Eight",    "Nine",
        "Ten",       "Eleven",    "Twelve",    "Thirteen", "Fourteen",
        "Fifteen",   "Sixteen",   "Seventeen", "Eighteen", "Nineteen"
    };

    /**
     * Word table for multiples of ten (20–90).
     * Indices 0 and 1 are intentionally empty — values below 20 are
     * handled entirely by {@link #ONES_AND_TEENS}.
     */
    private static final String[] TENS_MULTIPLES = {
        "", "", "Twenty", "Thirty", "Forty", "Fifty",
        "Sixty", "Seventy", "Eighty", "Ninety"
    };

    /** Private constructor — utility class; not meant to be instantiated. */
    private AmountToWords() { }

    /**
     * Converts a {@link BigDecimal} amount to Indian-English currency words.
     *
     * <p>The rupee part is broken down using the Indian numbering system
     * (Crore, Lakh, Thousand, Hundred). The paise part (fractional cents)
     * is appended as "And N Paise" when non-zero. The result always ends
     * with " Only" as required for negotiable instruments.
     *
     * <p>Return values for edge cases:
     * <ul>
     *   <li>{@code null} input → returns {@code null}</li>
     *   <li>Negative amount → returns {@code null}</li>
     *   <li>Zero ({@code 0.00}) → returns {@code "Zero Rupees Only"}</li>
     * </ul>
     *
     * @param amount the monetary amount to convert; may be null
     * @return Indian-English words for the amount, or {@code null} for
     *         null/negative input
     */
    public static String convert(BigDecimal amount) {
        if (amount == null) return null;
        if (amount.compareTo(BigDecimal.ZERO) < 0) return null;
        if (amount.compareTo(BigDecimal.ZERO) == 0) return "Zero Rupees Only";

        // Separate the amount into whole rupees and paise (fractional cents × 100)
        long rupeeAmount = amount.longValue();
        int  paiseAmount = amount.remainder(BigDecimal.ONE)
                                 .multiply(new BigDecimal(100))
                                 .intValue();

        StringBuilder wordResult = new StringBuilder();

        if (rupeeAmount > 0) {
            wordResult.append(toIndianWords(rupeeAmount)).append(" Rupees");
        }

        if (paiseAmount > 0) {
            if (rupeeAmount > 0) wordResult.append(" And ");
            wordResult.append(twoDigitNumberToWords(paiseAmount)).append(" Paise");
        }

        wordResult.append(" Only");
        return wordResult.toString();
    }

    /**
     * Breaks a whole number into Indian place-value groups and builds
     * the corresponding word string.
     *
     * <p>Groups processed in order: Crore (10,000,000) → Lakh (100,000)
     * → Thousand (1,000) → Hundred (100) → remainder (1–99).
     *
     * @param totalRupees the whole-rupee amount; must be &gt; 0
     * @return space-separated Indian-English words, trimmed of trailing spaces
     */
    private static String toIndianWords(long totalRupees) {
        if (totalRupees == 0) return "";

        StringBuilder wordBuilder = new StringBuilder();
        long          remaining   = totalRupees;

        // ── Crore group (10,000,000) ─────────────────────────────────────────
        long croreCount = remaining / 10_000_000L;
        if (croreCount > 0) {
            wordBuilder.append(twoDigitNumberToWords((int) croreCount)).append(" Crore ");
            remaining %= 10_000_000L;
        }

        // ── Lakh group (100,000) ──────────────────────────────────────────────
        long lakhCount = remaining / 100_000L;
        if (lakhCount > 0) {
            wordBuilder.append(twoDigitNumberToWords((int) lakhCount)).append(" Lakh ");
            remaining %= 100_000L;
        }

        // ── Thousand group (1,000) ────────────────────────────────────────────
        long thousandCount = remaining / 1_000L;
        if (thousandCount > 0) {
            wordBuilder.append(twoDigitNumberToWords((int) thousandCount)).append(" Thousand ");
            remaining %= 1_000L;
        }

        // ── Hundred group (100) ───────────────────────────────────────────────
        long hundredCount = remaining / 100L;
        if (hundredCount > 0) {
            wordBuilder.append(ONES_AND_TEENS[(int) hundredCount]).append(" Hundred ");
            remaining %= 100L;
        }

        // ── Remainder 1–99 ────────────────────────────────────────────────────
        if (remaining > 0) {
            wordBuilder.append(twoDigitNumberToWords((int) remaining));
        }

        return wordBuilder.toString().trim();
    }

    /**
     * Converts an integer in the range 1–99 to English words.
     *
     * <p>Numbers 1–19 are looked up directly in {@link #ONES_AND_TEENS}.
     * Numbers 20–99 are composed from {@link #TENS_MULTIPLES} and
     * {@link #ONES_AND_TEENS} (e.g. 47 → "Forty Seven").
     *
     * @param numberUnder100 integer between 1 and 99 inclusive
     * @return English word(s) for the number, trimmed
     */
    private static String twoDigitNumberToWords(int numberUnder100) {
        if (numberUnder100 < 20) {
            return ONES_AND_TEENS[numberUnder100];
        }
        // Compose tens word + optional units word
        String tensWord = TENS_MULTIPLES[numberUnder100 / 10];
        String unitsWord = (numberUnder100 % 10 != 0)
                ? " " + ONES_AND_TEENS[numberUnder100 % 10]
                : "";
        return (tensWord + unitsWord).trim();
    }
}
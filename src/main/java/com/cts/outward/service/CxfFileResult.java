/*
 * ============================================================
 *  Project     : NavBharat CTS — Cheque Truncation System
 *  Module      : Outward Clearing — Service Layer
 *  File        : CxfFileResult.java
 *  Package     : com.cts.outward.service
 *  Author      : Umesh M.
 *  Created     : June 2026
 *
 * ──────────────────────────────────────────────────────────────
 *  PURPOSE
 * ──────────────────────────────────────────────────────────────
 *  Immutable value object (Java 21 record) representing the
 *  outcome of a single CXF (Clearing Exchange Format) file
 *  generation attempt for one batch.
 *
 *  CXF files are the RBI-mandated XML output files submitted to
 *  the ClearPay clearing platform after a batch is verified and
 *  approved. Each CXF file covers one batch and contains the
 *  MICR-coded cheque data in a format the clearing network accepts.
 *
 * ──────────────────────────────────────────────────────────────
 *  CALL FLOW — WHERE THIS OBJECT IS CREATED AND READ
 * ──────────────────────────────────────────────────────────────
 *
 *  CREATED BY  (producer):
 *  ───────────────────────
 *  CBSService.generateCxfFile(batchId)
 *    → On success: CxfFileResult.ok(fileName, filePath, count, total)
 *    → On failure: CxfFileResult.fail(fileName, errorMessage)
 *    → returns CxfFileResult to caller
 *
 *  READ BY  (consumers):
 *  ─────────────────────
 *  BatchDetailComposer
 *    onGenerateCxf()
 *      → calls CBSService.generateCxfFile(batchId)
 *      → receives CxfFileResult
 *      → if result.isSuccess()  → show success dialog with fileName,
 *                                  recordCount, totalAmount, generatedAt
 *      → if !result.isSuccess() → show error dialog with errorMsg
 *
 * ──────────────────────────────────────────────────────────────
 *  DESIGN NOTES
 * ──────────────────────────────────────────────────────────────
 *  - Implemented as a Java 21 record for maximum immutability and
 *    conciseness. All fields are final by record semantics.
 *  - Accessor methods are declared explicitly to satisfy any
 *    frameworks or callers that expect traditional getXxx() names
 *    rather than the record's default component accessors.
 *  - Two static factory methods (ok / fail) provide a clean,
 *    readable API at the call site:
 *        return CxfFileResult.ok(name, path, count, total);
 *        return CxfFileResult.fail(name, ex.getMessage());
 *  - On failure, filePath and totalAmount carry sentinel values
 *    (null / ZERO) — callers must check isSuccess() before using them.
 *
 *  CXF FILE NAMING CONVENTION (RBI CTS-2010):
 *    CXF_{bankCode}-CTS_{date}_{sequence}.xml
 *    e.g.: CXF_01-CTS_20240515_0001.xml
 * ============================================================
 */

package com.cts.outward.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Immutable result of a single CXF file generation attempt.
 *
 * <p>Created by {@code CBSService.generateCxfFile()} and consumed by
 * {@code BatchDetailComposer.onGenerateCxf()} to decide which dialog
 * to display: success summary or error notification.
 *
 * <h3>Usage pattern</h3>
 * <pre>
 *   CxfFileResult result = cbsService.generateCxfFile(batchId);
 *   if (result.isSuccess()) {
 *       // display: result.getFileName(), result.getRecordCount(),
 *       //          result.getTotalAmount(), result.getGeneratedAt()
 *   } else {
 *       // display: result.getErrorMsg()
 *   }
 * </pre>
 *
 * @author Umesh M.
 * @see CBSService
 */
public record CxfFileResult(

        /**
         * RBI-compliant CXF output filename, e.g. {@code CXF_01-CTS_20240515_0001.xml}.
         * Always non-null — present on both success and failure so error dialogs
         * can reference which file generation failed.
         */
        String fileName,

        /**
         * Absolute filesystem path where the CXF file was written on the server.
         * {@code null} on failure — callers must check {@link #isSuccess()} first.
         *
         * <p>Used by the composer to offer a download link or show the file location
         * in the post-generation success dialog.
         */
        String filePath,

        /**
         * Number of cheque records included in this CXF file.
         * {@code 0} on failure.
         *
         * <p>Displayed in the success dialog as "Instruments cleared: N".
         */
        int recordCount,

        /**
         * Sum of all cheque amounts included in this CXF file, in INR.
         * {@link BigDecimal#ZERO} on failure. Never {@code null}.
         *
         * <p>Displayed in the success dialog as the total clearing amount.
         */
        BigDecimal totalAmount,

        /**
         * Server timestamp at the moment this result was constructed.
         * Captured inside the factory methods so it reflects actual generation time.
         *
         * <p>Shown in the success dialog as "Generated at: DD-MMM-YYYY HH:mm:ss".
         */
        LocalDateTime generatedAt,

        /**
         * {@code true} if the CXF file was written to disk without error.
         * {@code false} if any exception occurred during generation or file I/O.
         *
         * <p>This is the primary branch condition in {@code BatchDetailComposer}.
         */
        boolean success,

        /**
         * Human-readable error description when {@link #success} is {@code false}.
         * {@code null} on a successful generation.
         *
         * <p>Displayed as-is in the error notification dialog shown to the Maker/Verifier.
         */
        String errorMsg

) {

    // ══════════════════════════════════════════════════════════════════
    // STATIC FACTORY METHODS
    // ══════════════════════════════════════════════════════════════════

    /**
     * Factory for a <b>successful</b> CXF generation result.
     *
     * <p><b>Caller:</b> {@code CBSService.generateCxfFile()} — invoked after
     * the CXF file has been written to disk without error.
     *
     * <pre>
     *   return CxfFileResult.ok(
     *       "CXF_01-CTS_20240515_0001.xml",
     *       "/opt/navbharat/cxf/CXF_01-CTS_20240515_0001.xml",
     *       chequeCount,
     *       totalAmount
     *   );
     * </pre>
     *
     * @param fileName  RBI-compliant CXF filename (without path)
     * @param filePath  absolute path of the written file on the server
     * @param count     number of cheque records included in the file
     * @param total     total INR amount of all cheques in the file
     * @return success result with {@code generatedAt} = now, {@code errorMsg} = null
     */
    public static CxfFileResult ok(String fileName, String filePath,
                                   int count, BigDecimal total) {
        return new CxfFileResult(fileName, filePath, count, total,
                LocalDateTime.now(), true, null);
    }

    /**
     * Factory for a <b>failed</b> CXF generation result.
     *
     * <p><b>Caller:</b> {@code CBSService.generateCxfFile()} — invoked inside
     * a catch block when file writing, XML generation, or DB lookup fails.
     *
     * <pre>
     *   } catch (IOException ex) {
     *       return CxfFileResult.fail("CXF_01-CTS_20240515_0001.xml", ex.getMessage());
     *   }
     * </pre>
     *
     * @param fileName  CXF filename that was being generated when the error occurred
     * @param errorMsg  exception message or human-readable failure reason
     * @return failure result with {@code filePath} = null, {@code recordCount} = 0,
     *         {@code totalAmount} = ZERO, {@code success} = false
     */
    public static CxfFileResult fail(String fileName, String errorMsg) {
        return new CxfFileResult(fileName, null, 0, BigDecimal.ZERO,
                LocalDateTime.now(), false, errorMsg);
    }

    // ══════════════════════════════════════════════════════════════════
    // EXPLICIT ACCESSOR METHODS
    // ──────────────────────────────────────────────────────────────────
    // Java records auto-generate component accessors with the same name
    // as the field (e.g. fileName(), filePath()). These explicit getXxx()
    // wrappers are provided for callers that follow JavaBean conventions
    // (ZK EL expressions, Hibernate mappers, or older utility code).
    // ══════════════════════════════════════════════════════════════════

    /**
     * Returns whether the CXF file was generated and written successfully.
     *
     * <p>The primary branch condition read by {@code BatchDetailComposer.onGenerateCxf()}.
     *
     * @return {@code true} if CXF file exists on disk; {@code false} on any error
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Returns the RBI-compliant CXF output filename (without directory path).
     *
     * @return filename string, e.g. {@code CXF_01-CTS_20240515_0001.xml}; never null
     */
    public String getFileName() {
        return fileName;
    }

    /**
     * Returns the absolute server-side path of the generated CXF file.
     *
     * @return absolute path string; {@code null} if generation failed
     */
    public String getFilePath() {
        return filePath;
    }

    /**
     * Returns the number of cheque instruments included in the CXF file.
     *
     * @return instrument count; {@code 0} on failure
     */
    public int getRecordCount() {
        return recordCount;
    }

    /**
     * Returns the total INR amount of all cheques in the CXF file.
     *
     * @return total amount; {@link BigDecimal#ZERO} on failure; never null
     */
    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    /**
     * Returns the server timestamp captured at CXF generation time.
     *
     * @return generation timestamp; never null
     */
    public LocalDateTime getGeneratedAt() {
        return generatedAt;
    }

    /**
     * Returns the error message when generation failed.
     *
     * @return human-readable failure reason; {@code null} on success
     */
    public String getErrorMsg() {
        return errorMsg;
    }
}
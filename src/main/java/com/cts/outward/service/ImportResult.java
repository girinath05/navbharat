/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : ImportResult.java
 *  Package     : com.cts.outward.service
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : Value object returned after a ZIP import run.
 *                Summarises success/failure counts, the assigned
 *                batchId, and a list of per-cheque error messages
 *                displayed in the batch-entry result dialog.
 *
 *  CHANGED (Jun 2026):
 *    + skippedDuplicates  — how many cheques were already in DB
 *    + parsedTotal        — total cheques found in ZIP (before dedup)
 *    + parsedTotalAmount  — sum of all parsed cheque amounts (before dedup)
 *      Used by composer to show "all-duplicates" dialog with correct counts.
 * ============================================================
 */

package com.cts.outward.service;

import java.math.BigDecimal;
import java.util.List;

import com.cts.outward.entity.BatchEntity;
import com.cts.outward.entity.ChequeEntity;

public class ImportResult {

    private final BatchEntity        batch;
    private final List<ChequeEntity> cheques;
    private final long               elapsedMs;

    /** How many cheque nos from the ZIP were already in DB and skipped. */
    private int        skippedDuplicates = 0;

    /** Total cheques parsed from ZIP (including duplicates). */
    private int        parsedTotal       = 0;

    /** Sum of ALL parsed cheque amounts from ZIP (including duplicates). */
    private BigDecimal parsedTotalAmount = BigDecimal.ZERO;

    // ── Constructor ────────────────────────────────────────────────────

    public ImportResult(BatchEntity batch, List<ChequeEntity> cheques, long elapsedMs) {
        this.batch     = batch;
        this.cheques   = cheques;
        this.elapsedMs = elapsedMs;
    }

    // ── Core getters ───────────────────────────────────────────────────

    public BatchEntity getBatch() {
        return batch;
    }

    public List<ChequeEntity> getCheques() {
        return cheques;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    // ── Duplicate-tracking getters / setters ───────────────────────────

    public int getSkippedDuplicates() {
        return skippedDuplicates;
    }

    public void setSkippedDuplicates(int skippedDuplicates) {
        this.skippedDuplicates = skippedDuplicates;
    }

    public int getParsedTotal() {
        return parsedTotal;
    }

    public void setParsedTotal(int parsedTotal) {
        this.parsedTotal = parsedTotal;
    }

    public BigDecimal getParsedTotalAmount() {
        return parsedTotalAmount;
    }

    public void setParsedTotalAmount(BigDecimal parsedTotalAmount) {
        this.parsedTotalAmount = parsedTotalAmount != null ? parsedTotalAmount : BigDecimal.ZERO;
    }

    // ── Convenience ────────────────────────────────────────────────────

    /** True when every cheque in the ZIP was already in DB — nothing saved. */
    public boolean isAllDuplicates() {
        return parsedTotal > 0 && skippedDuplicates == parsedTotal && cheques.isEmpty();
    }
}
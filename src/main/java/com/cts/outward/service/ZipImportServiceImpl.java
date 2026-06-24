/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : ZipImportServiceImpl.java
 *  Package     : com.cts.outward.service
 *  Author      : Umesh M.
 *  Date        : 24-06-2026
 *  Description : Concrete implementation of ZipImportService.
 *                Converts BatchModel → BatchEntity and each
 *                ChequeModel → ChequeEntity, then delegates to
 *                BatchDAO.saveBatch() and ChequeDAO.saveCheques()
 *                in order.
 *
 *  CHANGED (Jun 2026):
 *    - Removed RuntimeException throw on all-duplicates.
 *      Instead: fills ImportResult.skippedDuplicates, parsedTotal,
 *      parsedTotalAmount and returns early — no DB writes.
 *    - Composer detects result.isAllDuplicates() and shows
 *      a dedicated "All Cheques Already Present" dialog.
 *    - Partial-duplicate path unchanged (saves surviving cheques,
 *      sets skippedDuplicates so mismatch dialog can show counts).
 * ============================================================
 */

package com.cts.outward.service;

import com.cts.outward.enums.BatchStatus;
import com.cts.outward.enums.ChequeStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.cts.outward.dao.BatchDAO;
import com.cts.outward.dao.BatchDAOImpl;
import com.cts.outward.dao.ChequeDAO;
import com.cts.outward.dao.ChequeDAOImpl;
import com.cts.outward.entity.BatchEntity;
import com.cts.outward.entity.ChequeEntity;
import com.cts.outward.parser.CtsParser;
import com.cts.outward.parser.CtsZipParserImpl;

public class ZipImportServiceImpl implements ZipImportService {

    private static final Logger LOG = Logger.getLogger(ZipImportServiceImpl.class.getName());

    private final CtsParser ctsParser = new CtsZipParserImpl();
    private final ChequeDAO chequeDAO = new ChequeDAOImpl();
    private final BatchDAO  batchDAO  = new BatchDAOImpl();

    // ══════════════════════════════════════════════════════════════════
    // PRIMARY IMPORT — with existing batch id (Step-2 scan modal path)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Primary import path (Step 2 of scan modal).
     * Parses ZIP, deduplicates against DB, maps models to entities,
     * persists to cts_batches + cts_cheques.
     *
     * @param zipBytes        raw bytes from ZK UploadEvent
     * @param zipName         original filename e.g. MUM01_20260610.zip
     * @param branchCode      branch code from session e.g. MUM01
     * @param createdBy       logged-in Maker username from session
     * @param existingBatchId batchId of the Draft batch from Step 1
     * @return ImportResult with parsed counts, skipped duplicates, success flag
     */
    @Override
    public ImportResult importZip(byte[] zipBytes, String zipName,
                                  String branchCode, String createdBy,
                                  String existingBatchId) {

        long startMs = System.currentTimeMillis();

        // ── 1. Parse ZIP ──────────────────────────────────────────────
        CtsParser.ParseResult parsed   = ctsParser.parse(zipBytes, zipName);
        BatchEntity           batch    = parsed.getBatch();
        List<ChequeEntity>    cheques  = parsed.getCheques();

        int        parsedTotal       = cheques.size();
        BigDecimal parsedTotalAmount = cheques.stream()
                .map(c -> c.getAmount() != null ? c.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // ── 2. Duplicate guard ────────────────────────────────────────
        List<String> incomingNos   = cheques.stream()
                .map(ChequeEntity::getChequeNo)
                .collect(Collectors.toList());
        Set<String>  alreadyExists = chequeDAO.findExistingChequeNos(incomingNos);

        int skipped = alreadyExists.size();

        if (!alreadyExists.isEmpty()) {
            cheques = cheques.stream()
                    .filter(c -> !alreadyExists.contains(c.getChequeNo()))
                    .collect(Collectors.toList());
            LOG.warning("Duplicate cheques skipped on import: " + alreadyExists);
        }

        // ── 3. ALL duplicates — return without any DB write ───────────
        if (cheques.isEmpty()) {
            LOG.warning("All " + parsedTotal + " cheque(s) from " + zipName
                    + " already exist in system. Nothing saved.");

            // We need a batch shell to carry metadata back to the composer.
            // Use existingBatchId if provided, else the parsed one.
            if (existingBatchId != null && !existingBatchId.isBlank())
                batch.setBatchId(existingBatchId);

            ImportResult allDupResult = new ImportResult(batch, Collections.emptyList(),
                    System.currentTimeMillis() - startMs);
            allDupResult.setSkippedDuplicates(skipped);
            allDupResult.setParsedTotal(parsedTotal);
            allDupResult.setParsedTotalAmount(parsedTotalAmount);
            return allDupResult;
        }

        // ── 4. Partial or no duplicates — persist ─────────────────────
        // Recalculate actual amount from surviving cheques (null-safe).
        BigDecimal actualAmount = cheques.stream()
                .map(c -> c.getAmount() != null ? c.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        batch.setTotalAmount(actualAmount);

        if (existingBatchId != null && !existingBatchId.isBlank()) {
            batch.setBatchId(existingBatchId);
            batchDAO.updateBatchActualCounts(existingBatchId, cheques.size(),
                    actualAmount, BatchStatus.DRAFT.db());
        } else {
            if (branchCode != null && !branchCode.isBlank())
                batch.setBranchCode(branchCode);
            if (createdBy != null && !createdBy.isBlank())
                batch.setCreatedBy(createdBy);
            batch.setCreatedAt(LocalDateTime.now());
            batch.setUpdatedAt(LocalDateTime.now());
            batchDAO.saveBatch(batch);
        }

        for (ChequeEntity cheque : cheques)
            cheque.setBatchId(batch.getBatchId());

        chequeDAO.saveCheques(cheques);

        long elapsedMs = System.currentTimeMillis() - startMs;

        ImportResult result = new ImportResult(batch, cheques, elapsedMs);
        result.setSkippedDuplicates(skipped);
        result.setParsedTotal(parsedTotal);
        result.setParsedTotalAmount(parsedTotalAmount);
        return result;
    }

    // ══════════════════════════════════════════════════════════════════
    // CONVENIENCE OVERLOAD — no existing batch (direct upload path)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Convenience overload - delegates to 5-param variant with existingBatchId=null.
     * Used when no pre-created batch exists (legacy/direct import path).
     *
     * @param zipBytes   raw bytes from ZK UploadEvent
     * @param zipName    original filename; used for logging
     * @param branchCode branch code from session
     * @param createdBy  logged-in Maker username from session
     * @return ImportResult - see 5-param overload for details
     */
    @Override
    public ImportResult importZip(byte[] zipBytes, String zipName,
                                  String branchCode, String createdBy) {
        return importZip(zipBytes, zipName, branchCode, createdBy, null);
    }
}
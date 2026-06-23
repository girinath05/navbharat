/*
 * Project     : Navbharat CTS Outward
 * File        : VerificationOneServiceImpl.java
 * Package     : com.cts.outward.service
 * Author      : Anusha M.
 * Created     : June 2026
 * Description : Business logic implementation for the Verification I (Checker) screen.
 *               Orchestrates batch-status transitions, cheque actions (accept/reject/refer),
 *               and CBS account validation via injected DAO and CBS service dependencies.
 */
package com.cts.outward.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.cts.outward.dao.BatchDAO;
import com.cts.outward.dao.ChequeDAO;
import com.cts.outward.entity.BatchEntity;
import com.cts.outward.entity.ChequeEntity;
import com.cts.outward.enums.BatchStatus;
import com.cts.outward.enums.ChequeStatus;
import com.cts.outward.model.BatchSummary;
import com.cts.outward.model.CbsAccountDetails;
import com.cts.outward.model.CbsAccountDetails.LookupState;
import com.fasterxml.jackson.databind.JsonNode;

public class VerificationOneServiceImpl implements VerificationOneService {

    private static final Logger LOGGER  = Logger.getLogger(VerificationOneServiceImpl.class.getName());
    private static final String EM_DASH = "\u2014";

    private final BatchDAO   batchDAO;
    private final ChequeDAO  chequeDAO;
    private final CBSService cbsService;

    public VerificationOneServiceImpl(BatchDAO batchDAO, ChequeDAO chequeDAO, CBSService cbsService) {
        this.batchDAO   = batchDAO;
        this.chequeDAO  = chequeDAO;
        this.cbsService = cbsService;
    }

    // ── Batch list (Phase 1) ─────────────────────────────────────────────

    /**
     * Builds the Phase 1 batch list in three steps:
     *  1. Load all V1-pending cheques and group them by batchId.
     *  2. Filter the full batch list to those with pending work and a verifiable status.
     *  3. For each surviving batch, count already-processed (VERIFIED or REJECTED) cheques.
     */
    @Override
    public List<BatchSummary> getVerifiableBatchSummaries() {
        List<ChequeEntity> v1PendingCheques =
                chequeDAO.loadChequesByVerLevel("V1", ChequeStatus.V1_PENDING.db());

        Map<String, Long> pendingCountByBatchId = v1PendingCheques.stream()
                .collect(Collectors.groupingBy(ChequeEntity::getBatchId, Collectors.counting()));

        List<BatchEntity> verifiableBatches = batchDAO.loadAllBatches().stream()
                .filter(batch -> pendingCountByBatchId.containsKey(batch.getBatchId()))
                .filter(batch -> {
                    BatchStatus batchStatus = BatchStatus.fromDb(batch.getStatus());
                    return batchStatus == BatchStatus.READY_FOR_VERIFICATION
                        || batchStatus == BatchStatus.VERIFICATION_IN_PROGRESS;
                })
                .collect(Collectors.toList());

        List<BatchSummary> summaries = new ArrayList<>();
        for (BatchEntity batch : verifiableBatches) {
            long pendingCount   = pendingCountByBatchId.getOrDefault(batch.getBatchId(), 0L);
            long processedCount = chequeDAO.countV1ProcessedForBatch(batch.getBatchId());
            String createdAt = batch.getCreatedAt() != null ? batch.getCreatedAt().toString() : EM_DASH;
            summaries.add(new BatchSummary(
                    batch.getBatchId(),
                    batch.getTotalCheques(),
                    pendingCount,
                    processedCount,
                    createdAt,
                    BatchStatus.fromDb(batch.getStatus())));
        }
        return summaries;
    }

    // ── Batch open (Phase 1 → Phase 2) ──────────────────────────────────

    @Override
    public List<ChequeEntity> openBatchForVerification(String batchId) {
        BatchEntity batch = batchDAO.getBatchById(batchId);
        if (batch != null
                && BatchStatus.fromDb(batch.getStatus()) == BatchStatus.READY_FOR_VERIFICATION) {
            batchDAO.updateBatchStatus(batchId, BatchStatus.VERIFICATION_IN_PROGRESS.db());
            LOGGER.info("Batch " + batchId + " transitioned \u2192 VERIFICATION_IN_PROGRESS");
        }

        List<ChequeEntity> allCheques = chequeDAO.loadChequesForBatch(batchId);
        if (allCheques == null) return new ArrayList<>();

        return allCheques.stream()
                .filter(cheque -> ChequeStatus.V1_PENDING.db().equals(cheque.getStatus()))
                .collect(Collectors.toList());
    }

    // ── Cheque verification popup ────────────────────────────────────────

    @Override
    public CbsAccountDetails getCbsAccountDetails(String accountNumber, String payeeNameOnCheque) {
        if (accountNumber == null || accountNumber.isBlank()) {
            return buildUnavailableDetails();
        }

        JsonNode accountFields = cbsService.lookupAccountFields(accountNumber);
        if (accountFields == null || accountFields.isMissingNode()) {
            return buildNotFoundDetails();
        }

        String  accountHolderName = accountFields.path("accountHolderName").path("stringValue").asText(null);
        boolean isActive          = accountFields.path("active").path("booleanValue").asBoolean(false);
        String  isNewAccount      = cbsService.getIsNewAccount(accountNumber);

        String accountStatus      = isActive ? "Active"    : "Inactive";
        String accountStatusSclass = isActive ? "ch-active" : "ch-inactive";
        String newAccountSclass   = "Yes".equalsIgnoreCase(isNewAccount) ? "ch-new-acc" : "ch-not-new";

        // Compare CBS account holder name to the payee name written on the cheque
        String payeeMatchLabel;
        String payeeMatchSclass;
        if (accountHolderName != null && payeeNameOnCheque != null) {
            boolean namesMatch  = accountHolderName.trim().equalsIgnoreCase(payeeNameOnCheque.trim());
            payeeMatchLabel  = namesMatch ? "Match"        : "Mismatch";
            payeeMatchSclass = namesMatch ? "cbs-match-ok" : "cbs-match-fail";
        } else {
            payeeMatchLabel  = EM_DASH;
            payeeMatchSclass = "";
        }

        return new CbsAccountDetails(
                LookupState.FOUND,
                accountHolderName != null ? accountHolderName : EM_DASH,
                accountStatus, accountStatusSclass,
                isNewAccount, newAccountSclass,
                payeeMatchLabel, payeeMatchSclass,
                isActive);
    }

    /**
     * Validates that the CBS account exists and is active before accepting the cheque.
     * Returns null on success; returns a user-facing error message on CBS validation failure.
     */
    @Override
    public String validateAndAcceptCheque(Long chequeId, String accountNumber, String verifiedBy) {
        if (accountNumber != null && !accountNumber.isBlank()) {
            JsonNode accountFields = cbsService.lookupAccountFields(accountNumber);
            if (accountFields == null || accountFields.isMissingNode()) {
                return "Account not found in CBS. Cannot accept.";
            }
            if (!accountFields.path("active").path("booleanValue").asBoolean(false)) {
                return "Account is inactive in CBS. Cannot accept.";
            }
        }
        chequeDAO.applyVerifierAction(chequeId,
                ChequeStatus.VERIFIED.db(), "V1", "ACCEPTED", verifiedBy, null);
        LOGGER.info("Cheque " + chequeId + " accepted by " + verifiedBy);
        return null;
    }

    @Override
    public void rejectCheque(Long chequeId, String rejectedBy, String rejectionReason) {
        chequeDAO.applyVerifierAction(chequeId,
                ChequeStatus.REJECTED.db(), "V1", "REJECTED", rejectedBy, rejectionReason);
        LOGGER.info("Cheque " + chequeId + " rejected by " + rejectedBy + " \u2014 reason: " + rejectionReason);
    }

    @Override
    public void referCheque(Long chequeId, String referredBy, String referReason) {
        chequeDAO.referToVerificationTwo(chequeId, referredBy, referReason);
        LOGGER.info("Cheque " + chequeId + " referred by " + referredBy + " \u2014 reason: " + referReason);
    }

    // ── Batch finalization ───────────────────────────────────────────────

    /**
     * Finalization logic:
     *   pendingCount  > 0  → cheques still awaiting action; batch stays VERIFICATION_IN_PROGRESS
     *   pendingCount == 0  → all cheques actioned; batch advances to VERIFIED
     *   pendingCount  < 0  → DB error indicator; skip to avoid an incorrect status advance
     */
    @Override
    public void checkAndFinalizeBatch(String batchId) {
        if (batchId == null) {
            LOGGER.warning("checkAndFinalizeBatch: null batchId \u2014 skipped");
            return;
        }
        long pendingCount = chequeDAO.countPendingVerificationForBatch(batchId);
        if (pendingCount < 0) {
            LOGGER.severe("checkAndFinalizeBatch: DB error for batch " + batchId + " \u2014 skipped");
            return;
        }
        if (pendingCount == 0) {
            batchDAO.updateBatchStatus(batchId, BatchStatus.VERIFIED.db());
            LOGGER.info("Batch " + batchId + " finalized \u2192 " + BatchStatus.VERIFIED.db());
        }
    }

    // ── Low-level reads ──────────────────────────────────────────────────

    @Override
    public List<BatchEntity> getAllBatches() {
        return batchDAO.loadAllBatches();
    }

    @Override
    public BatchEntity getBatchById(String batchId) {
        return batchDAO.getBatchById(batchId);
    }

    @Override
    public List<ChequeEntity> getChequesForBatch(String batchId) {
        return chequeDAO.loadChequesForBatch(batchId);
    }

    @Override
    public List<ChequeEntity> getV1PendingCheques(String status) {
        return chequeDAO.loadChequesByVerLevel("V1", status);
    }

    @Override
    public void updateBatchStatus(String batchId, String newStatus) {
        if (batchId == null || newStatus == null) {
            LOGGER.warning("updateBatchStatus: null argument \u2014 skipped");
            return;
        }
        batchDAO.updateBatchStatus(batchId, newStatus);
        LOGGER.info("Batch " + batchId + " status updated to " + newStatus);
    }

    @Override
    public JsonNode lookupAccountFields(String accountNumber) {
        return cbsService.lookupAccountFields(accountNumber);
    }

    @Override
    public String getIsNewAccount(String accountNumber) {
        return cbsService.getIsNewAccount(accountNumber);
    }

    // ── Private helpers ──────────────────────────────────────────────────

    /** Used when the cheque has no account number — no CBS lookup was attempted. */
    private CbsAccountDetails buildUnavailableDetails() {
        return new CbsAccountDetails(
                LookupState.UNAVAILABLE,
                EM_DASH, EM_DASH, "ch-cbs-unknown",
                EM_DASH, "ch-cbs-unknown",
                EM_DASH, "", false);
    }

    /** Used when Firestore returns no document for the given account number. */
    private CbsAccountDetails buildNotFoundDetails() {
        return new CbsAccountDetails(
                LookupState.NOT_FOUND,
                EM_DASH, "Not found", "ch-cbs-unknown",
                EM_DASH, "ch-cbs-unknown",
                EM_DASH, "", false);
    }
}
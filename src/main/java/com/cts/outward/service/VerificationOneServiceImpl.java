
/*
 * File        : VerificationOneServiceImpl.java
 * Package     : com.cts.outward.service
 * Description : Implements all business logic for the Verification I (Checker) screen.
 *
 *               Responsibilities
 *               ─────────────────────────────────────────────────────────────
 *               • Decide which batches the verifier should see and in what order.
 *               • Drive batch status transitions
 *                   (READY_FOR_VERIFICATION → VERIFICATION_IN_PROGRESS → VERIFIED).
 *               • Load and filter cheques for the active batch.
 *               • Persist accept / reject / refer decisions to the database.
 *               • Update in-memory entity objects after each action so the
 *                 UI counters stay accurate without an extra database round-trip.
 *               • Validate CBS account rules before accepting a cheque.
 *               • Fetch CBS account details for the verification popup.
 *
 *               What this class must NOT do
 *               ─────────────────────────────────────────────────────────────
 *               • Reference any ZK UI components (Label, Button, Listbox, etc.).
 *               • Build CSS class name strings for ZK sclass attributes.
 *               • Know anything about how cheque data is rendered on screen.
 *
 *               Database access
 *               ─────────────────────────────────────────────────────────────
 *               All database calls go through the BatchDAO and ChequeDAO
 *               interfaces so the service can be tested without a live database.
 *               CBS calls go through the CBSService interface.
 */
package com.cts.outward.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** Used whenever a display value is absent — shown as a dash on screen. */
    private static final String DISPLAY_EMPTY = "\u2014"; // em-dash character

    private final BatchDAO   batchDAO;
    private final ChequeDAO  chequeDAO;
    private final CBSService cbsService;

    /**
     * Constructor — all three collaborators are injected so they can be
     * replaced with test doubles in unit tests.
     *
     * @param batchDAO   data access object for the cts_batches table
     * @param chequeDAO  data access object for the cts_cheques table
     * @param cbsService gateway to the Core Banking System
     */
    public VerificationOneServiceImpl(BatchDAO batchDAO, ChequeDAO chequeDAO, CBSService cbsService) {
        this.batchDAO   = batchDAO;
        this.chequeDAO  = chequeDAO;
        this.cbsService = cbsService;
    }

    // ══════════════════════════════════════════════════════════════════════
    // 1. BATCH LIST
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Builds the Phase-1 batch summary list for the verifier's screen.
     *
     * Algorithm (explained step by step so future maintainers understand why):
     *
     * Step 1 — Find every V1_PENDING cheque across all batches.
     *           Group them by batch ID to get the "still pending" count per batch.
     *
     * Step 2 — Also collect batch IDs that have already-processed V1 cheques.
     *           This ensures batches where all cheques are done still appear in
     *           the list (so the verifier can review their completed work).
     *
     * Step 3 — Fetch only the batch header rows we actually need by ID.
     *           Avoids a full table scan of the batches table.
     *
     * Step 4 — Fetch accept + reject + refer counts per batch in one query
     *           to avoid N+1 queries (one per batch).
     *
     * Step 5 — Build one BatchSummary per batch and sort:
     *           READY_FOR_VERIFICATION first → VERIFICATION_IN_PROGRESS → VERIFIED.
     *           Within each group, newest batch ID first.
     */
    @Override
    public List<BatchSummary> getVerifiableBatchSummaries() {

        // Step 1: load all V1_PENDING cheques across every batch
        List<ChequeEntity> v1PendingCheques =
                chequeDAO.loadAllPendingV1ChequesAcrossAllBatches("V1", ChequeStatus.V1_PENDING.db());

        // Count how many V1_PENDING cheques each batch still has
        Map<String, Long> pendingCountByBatchId = v1PendingCheques.stream()
                .collect(Collectors.groupingBy(ChequeEntity::getBatchId, Collectors.counting()));

        // Step 2: union of batches with pending work + batches with completed V1 work
        Set<String> allRelevantBatchIds = new HashSet<>(pendingCountByBatchId.keySet());
        Set<String> batchIdsWithCompletedV1Work = chequeDAO.loadBatchIdsWithV1ProcessedCheques();
        allRelevantBatchIds.addAll(batchIdsWithCompletedV1Work);

        if (allRelevantBatchIds.isEmpty()) {
            LOGGER.info("getVerifiableBatchSummaries: no V1-relevant batches found in database");
            return new ArrayList<>();
        }

        // Step 3: fetch only the batch rows we need
        List<BatchEntity> relevantBatches = batchDAO.loadBatchesByIds(allRelevantBatchIds);
        if (relevantBatches.isEmpty()) {
            LOGGER.warning("getVerifiableBatchSummaries: batch IDs found in cheques table "
                    + "but no matching rows in cts_batches — possible data inconsistency. "
                    + "Affected batch IDs: " + allRelevantBatchIds);
            return new ArrayList<>();
        }

        // Step 4: processed-cheque count per batch (one query instead of N)
        Map<String, Long> processedCountByBatchId =
                chequeDAO.countV1ProcessedForBatches(allRelevantBatchIds);

        // Step 5: assemble one summary row per batch
        List<BatchSummary> summaries = new ArrayList<>();
        for (BatchEntity batch : relevantBatches) {
            long pendingCount   = pendingCountByBatchId.getOrDefault(batch.getBatchId(), 0L);
            long processedCount = processedCountByBatchId.getOrDefault(batch.getBatchId(), 0L);
            String createdAtDisplay = batch.getCreatedAt() != null
                    ? batch.getCreatedAt().toString()
                    : DISPLAY_EMPTY;

            summaries.add(new BatchSummary(
                    batch.getBatchId(),
                    batch.getTotalCheques(),
                    pendingCount,
                    processedCount,
                    createdAtDisplay,
                    BatchStatus.fromDb(batch.getStatus())));
        }

        // Sort: active work surfaces first; newest batch first within each status group
        summaries.sort((batchA, batchB) -> {
            int statusDiff = Integer.compare(
                    batchSortPriority(batchA.getStatus()),
                    batchSortPriority(batchB.getStatus()));
            // If same status group, sort descending by batch ID (newest first)
            return statusDiff != 0 ? statusDiff
                    : batchB.getBatchId().compareTo(batchA.getBatchId());
        });

        return summaries;
    }

    /**
     * Returns a numeric priority used to sort batches so that batches still
     * needing work appear before completed ones.
     *
     * Priority 1 = READY_FOR_VERIFICATION   (not yet started — highest urgency)
     * Priority 2 = VERIFICATION_IN_PROGRESS (started but not finished)
     * Priority 3 = VERIFIED                 (all done — shown at the bottom for reference)
     * Priority 4 = anything else            (unexpected status — shown last)
     */
    private int batchSortPriority(BatchStatus status) {
        switch (status) {
            case READY_FOR_VERIFICATION:   return 1;
            case VERIFICATION_IN_PROGRESS: return 2;
            case VERIFIED:                 return 3;
            default:                       return 4;
        }
    }

    /**
     * {@inheritDoc}
     *
     * Implementation: all four filter criteria (text, status, date range) are
     * evaluated together in a single stream pass so only one iteration is needed
     * regardless of how many filters are active.
     */
    @Override
    public List<BatchSummary> filterBatchSummaries(
            List<BatchSummary> allBatches,
            String searchText,
            String statusValue,
            Date   fromDate,
            Date   toDate) {

        // Normalise once before the loop to avoid repeated null checks inside the lambda
        String lowerCaseSearch = searchText != null ? searchText.toLowerCase() : "";

        return allBatches.stream()
                .filter(summary -> {
                    // --- Text filter: batch ID contains the typed text ---
                    if (!lowerCaseSearch.isEmpty()
                            && !summary.getBatchId().toLowerCase().contains(lowerCaseSearch))
                        return false;

                    // --- Status filter: "ALL" or null means "show every status" ---
                    if (statusValue != null && !statusValue.isBlank() && !"ALL".equals(statusValue)
                            && !statusValue.equals(summary.getStatus().db()))
                        return false;

                    // --- Date range filter: compare batch creation date against the chosen range ---
                    if (fromDate != null || toDate != null) {
                        Date batchCreatedDate = parseDateString(summary.getCreatedAt());
                        if (batchCreatedDate != null) {
                            if (fromDate != null && batchCreatedDate.before(fromDate)) return false;
                            if (toDate   != null && batchCreatedDate.after(toDate))   return false;
                        }
                    }
                    return true;
                })
                .collect(Collectors.toList());
    }

    /**
     * {@inheritDoc}
     *
     * Loads a fresh copy of the batch row from the database each time it is
     * called, so the status is always up to date even if another user has
     * changed it.  Defaults to VERIFICATION_IN_PROGRESS as a safe fallback
     * (allows the verifier to continue working if the batch row is missing).
     */
    @Override
    public BatchStatus getBatchStatus(String batchId) {
        BatchEntity batch = batchDAO.getBatchById(batchId);
        return batch != null
                ? BatchStatus.fromDb(batch.getStatus())
                : BatchStatus.VERIFICATION_IN_PROGRESS; // defensive default — do not block the verifier
    }

    /**
     * {@inheritDoc}
     *
     * Business rule: a batch is read-only when and only when its status
     * is VERIFIED.  All other statuses (READY, IN_PROGRESS, etc.) allow edits.
     *
     * Keeping this rule in the service means the composer never compares
     * BatchStatus values directly — it only calls this boolean method.
     */
    @Override
    public boolean isBatchReadOnly(String batchId) {
        return getBatchStatus(batchId) == BatchStatus.VERIFIED;
    }

    /**
     * {@inheritDoc}
     *
     * Only the READY_FOR_VERIFICATION → VERIFICATION_IN_PROGRESS transition is
     * performed here.  All other statuses are left untouched so calling this
     * method on an already-open or already-verified batch is always safe.
     */
    @Override
    public void openBatchForVerification(String batchId) {
        BatchEntity batch = batchDAO.getBatchById(batchId);
        if (batch != null
                && BatchStatus.fromDb(batch.getStatus()) == BatchStatus.READY_FOR_VERIFICATION) {
            batchDAO.updateBatchStatus(batchId, BatchStatus.VERIFICATION_IN_PROGRESS.db());
            LOGGER.info("Batch " + batchId
                    + " status transitioned: READY_FOR_VERIFICATION → VERIFICATION_IN_PROGRESS");
        }
    }

  
    /**
     * {@inheritDoc}
     *
     * Text search checks three fields (cheque number, payee name, amount) and
     * treats them as OR — i.e. the cheque passes if ANY of the three match.
     * All four criteria (text, status, from-date, to-date) are AND-combined,
     * meaning a cheque must pass every active filter to appear in the result.
     */
    @Override
    public List<ChequeEntity> filterCheques(
            List<ChequeEntity> allCheques,
            String searchText,
            String statusValue,
            Date   fromDate,
            Date   toDate) {

        String lowerCaseSearch = searchText != null ? searchText.toLowerCase() : "";

        return allCheques.stream()
                .filter(cheque -> {
                    // --- Text filter: match cheque number, payee name, or amount ---
                    if (!lowerCaseSearch.isEmpty()) {
                        boolean matchesChequeNumber = cheque.getChequeNo() != null
                                && cheque.getChequeNo().toLowerCase().contains(lowerCaseSearch);
                        boolean matchesPayeeName    = cheque.getPayeeName() != null
                                && cheque.getPayeeName().toLowerCase().contains(lowerCaseSearch);
                        boolean matchesAmount       = cheque.getAmount() != null
                                && cheque.getAmount().toPlainString().contains(lowerCaseSearch);
                        if (!matchesChequeNumber && !matchesPayeeName && !matchesAmount)
                            return false;
                    }

                    // --- Status filter ---
                    if (statusValue != null && !statusValue.isBlank() && !"ALL".equals(statusValue)) {
                        ChequeStatus selectedStatus = ChequeStatus.fromDb(statusValue);
                        if (!selectedStatus.db().equals(cheque.getStatus())) return false;
                    }

                    // --- Date range filter ---
                    if (fromDate != null || toDate != null) {
                        Date chequeDate = parseChequeDate(cheque.getChequeDate());
                        if (chequeDate != null) {
                            if (fromDate != null && chequeDate.before(fromDate)) return false;
                            if (toDate   != null && chequeDate.after(toDate))   return false;
                        }
                    }
                    return true;
                })
                .collect(Collectors.toList());
    }

    /** {@inheritDoc} */
    @Override
    public long countByStatus(List<ChequeEntity> cheques, ChequeStatus status) {
        if (cheques == null) return 0;
        return cheques.stream()
                .filter(cheque -> status.db().equals(cheque.getStatus()))
                .count();
    }

    // ══════════════════════════════════════════════════════════════════════
    // 3. CHEQUE ACTIONS  (persist to database)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * {@inheritDoc}
     *
     * CBS validation rule: when an account number is present on the cheque,
     * the account must (a) exist in CBS and (b) be marked as active.
     * Cheques with no account number skip CBS validation and are accepted directly
     * (the verifier is expected to judge those manually from the cheque image).
     */
    @Override
    public String validateAndAcceptCheque(Long chequeId, String accountNumber, String acceptedBy) {
        // Only validate if an account number is actually printed on the cheque
        if (accountNumber != null && !accountNumber.isBlank()) {
            JsonNode accountFields = cbsService.lookupAccountFields(accountNumber);

            if (accountFields == null || accountFields.isMissingNode()) {
                return "Account not found in CBS. Cannot accept this cheque.";
            }
            if (!accountFields.path("active").path("booleanValue").asBoolean(false)) {
                return "Account is inactive in CBS. Cannot accept this cheque.";
            }
        }

        // All validations passed — persist the ACCEPT decision
        chequeDAO.applyVerifierAction(
                chequeId, ChequeStatus.VERIFIED.db(), "V1", "ACCEPTED", acceptedBy, null);
        LOGGER.info("Cheque " + chequeId + " ACCEPTED by verifier: " + acceptedBy);
        return null; // null return = success (no error message)
    }

    /**
     * {@inheritDoc}
     *
     * The rejection reason is stored in the database so it can be displayed
     * in reports and audit trails.
     */
    @Override
    public void rejectCheque(Long chequeId, String rejectedBy, String rejectionReason) {
        chequeDAO.applyVerifierAction(
                chequeId, ChequeStatus.REJECTED.db(), "V1", "REJECTED", rejectedBy, rejectionReason);
        LOGGER.info("Cheque " + chequeId + " REJECTED by verifier: " + rejectedBy
                + " | Reason: " + rejectionReason);
    }

    /**
     * {@inheritDoc}
     *
     * The DAO call sets ver_level = "V2" and is_referred = true in the database.
     * After this point the cheque belongs to the Verification II screen and will
     * no longer be returned by getAllV1ChequesForBatch() on the next page load.
     */
    @Override
    public void referCheque(Long chequeId, String referredBy, String referReason) {
        chequeDAO.referToVerificationTwo(chequeId, referredBy, referReason);
        LOGGER.info("Cheque " + chequeId + " REFERRED to Verification II by: " + referredBy
                + " | Reason: " + referReason);
    }

    /**
     * {@inheritDoc}
     *
     * Logic:
     *   pendingCount > 0  — work remains; batch stays VERIFICATION_IN_PROGRESS.
     *   pendingCount = 0  — all cheques actioned; batch advances to VERIFIED.
     *   pendingCount < 0  — DAO returned an error sentinel; skip update to avoid
     *                        writing a wrong status to the database.
     */
    @Override
    public void checkAndFinalizeBatch(String batchId) {
        if (batchId == null) {
            LOGGER.warning("checkAndFinalizeBatch called with a null batchId — skipped");
            return;
        }
        long pendingCount = chequeDAO.countPendingVerificationForBatch(batchId);

        if (pendingCount < 0) {
            // DAO signals a database error with a negative value — do not change batch status
            LOGGER.severe("checkAndFinalizeBatch: database error while counting pending cheques "
                    + "for batch " + batchId + " — batch status NOT changed");
            return;
        }
        if (pendingCount == 0) {
            // All cheques processed — mark the batch as fully verified
            batchDAO.updateBatchStatus(batchId, BatchStatus.VERIFIED.db());
            LOGGER.info("Batch " + batchId
                    + " fully processed — status updated to VERIFIED");
        }
        // pendingCount > 0: work is still in progress — no status change needed
    }

    // ══════════════════════════════════════════════════════════════════════
    // 4. IN-MEMORY HELPERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * {@inheritDoc}
     *
     * Business rules applied:
     *   1. Both the "status" field and the "verStatus" (verification status) field
     *      are updated together because the database stores them separately and
     *      they must always stay in sync.
     *   2. For a REFER action the "isReferred" flag is additionally set to true.
     *      This mirrors the is_referred database column that referToVerificationTwo()
     *      sets on the database side.
     */
    @Override
    public void applyActionToInMemoryCheque(ChequeEntity cheque, String newStatus) {
        if (cheque == null) return;
        cheque.setStatus(newStatus);
        cheque.setVerStatus(newStatus);

        // Extra flag required only for the REFER action
        if (ChequeStatus.REFERRED.db().equals(newStatus)) {
            cheque.setReferred(true);
        }
    }

    /**
     * {@inheritDoc}
     *
     * Scans from the beginning of the list every time so that, after the verifier
     * actions a cheque in the middle of the list, the popup jumps back to the
     * first remaining pending cheque rather than skipping cheques above the current
     * position.
     */
    @Override
    public int findNextPendingChequeIndex(List<ChequeEntity> allCheques) {
        if (allCheques == null) return -1;
        for (int index = 0; index < allCheques.size(); index++) {
            if (ChequeStatus.V1_PENDING.db().equals(allCheques.get(index).getStatus())) {
                return index;
            }
        }
        return -1; // -1 means every cheque has been actioned — batch is complete
    }

    // ══════════════════════════════════════════════════════════════════════
    // 5. CBS INTEGRATION
    // ══════════════════════════════════════════════════════════════════════

    /**
     * {@inheritDoc}
     *
     * This method intentionally does NOT produce CSS class name strings.
     * The CbsAccountDetails object carries LookupState and boolean flags;
     * the composer translates those into ZK sclass strings in its own helper
     * method (resolveCbsSclass) — keeping all presentation logic in the UI layer.
     *
     * Three possible outcomes:
     *   UNAVAILABLE — account number was blank; nothing to look up.
     *   NOT_FOUND   — CBS was queried but returned no matching account.
     *   FOUND       — CBS returned an account; all fields are populated.
     */
    @Override
    public CbsAccountDetails getCbsAccountDetails(String accountNumber, String payeeNameOnCheque) {
        // Guard: if the cheque has no account number, skip the CBS call entirely
        if (accountNumber == null || accountNumber.isBlank()) {
            return buildUnavailableCbsDetails();
        }

        JsonNode accountFields = cbsService.lookupAccountFields(accountNumber);
        if (accountFields == null || accountFields.isMissingNode()) {
            return buildNotFoundCbsDetails();
        }

        // Extract individual fields from the CBS JSON response
        String  holderName    = accountFields.path("accountHolderName").path("stringValue").asText(null);
        boolean isActive      = accountFields.path("active").path("booleanValue").asBoolean(false);
        String  isNewAccount  = cbsService.getIsNewAccount(accountNumber);

        // Name-match: compare CBS account holder name with the name on the cheque
        String payeeMatchLabel;
        boolean payeeNamesMatch;
        if (holderName != null && payeeNameOnCheque != null) {
            payeeNamesMatch = holderName.trim().equalsIgnoreCase(payeeNameOnCheque.trim());
            payeeMatchLabel = payeeNamesMatch ? "Match" : "Mismatch";
        } else {
            payeeNamesMatch = false;
            payeeMatchLabel = DISPLAY_EMPTY;
        }

        return new CbsAccountDetails(
                LookupState.FOUND,
                holderName   != null ? holderName : DISPLAY_EMPTY,
                isActive     ? "Active"           : "Inactive",
                isActive,               // accountActive flag — composer maps this to a CSS class
                isNewAccount,
                "Yes".equalsIgnoreCase(isNewAccount), // isNewAccount flag — composer maps to CSS
                payeeMatchLabel,
                payeeNamesMatch         // payeeNamesMatch flag — composer maps to CSS
        );
    }

    /**
     * Returns a placeholder CBS result for cheques that have no account number.
     * All display fields are set to em-dash so the popup never shows blank cells.
     */
    private CbsAccountDetails buildUnavailableCbsDetails() {
        return new CbsAccountDetails(
                LookupState.UNAVAILABLE,
                DISPLAY_EMPTY, // account holder name
                DISPLAY_EMPTY, // account status label
                false,         // isActive
                DISPLAY_EMPTY, // isNewAccount label
                false,         // isNewAccountFlag
                DISPLAY_EMPTY, // payee match label
                false          // payeeNamesMatch
        );
    }

    /**
     * Returns a placeholder CBS result for cheques whose account number
     * did not match any record in CBS.
     * "Not found" is shown for account status so the verifier knows a lookup
     * was attempted and explicitly failed (as opposed to simply not being run).
     */
    private CbsAccountDetails buildNotFoundCbsDetails() {
        return new CbsAccountDetails(
                LookupState.NOT_FOUND,
                DISPLAY_EMPTY,  // account holder name (unknown)
                "Not found",    // account status label — clearly signals a failed lookup
                false,          // isActive
                DISPLAY_EMPTY,  // isNewAccount label
                false,          // isNewAccountFlag
                DISPLAY_EMPTY,  // payee match label
                false           // payeeNamesMatch
        );
    }

    // ══════════════════════════════════════════════════════════════════════
    // 6. DATE HELPERS  (private — only used within this class)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * {@inheritDoc}
     *
     * Example: "2026-06-24T18:07:32" → "24/06/2026".
     * Only the date portion (before the "T") is used; the time is discarded.
     */
    @Override
    public String formatDisplayDate(String isoTimestamp) {
        if (isoTimestamp == null || isoTimestamp.isBlank() || DISPLAY_EMPTY.equals(isoTimestamp))
            return DISPLAY_EMPTY;
        try {
            // Strip the time portion if present ("2026-06-24T18:07:32" → "2026-06-24")
            String datePart = isoTimestamp.contains("T")
                    ? isoTimestamp.substring(0, 10)
                    : isoTimestamp.trim();
            return java.time.LocalDate.parse(datePart)
                    .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        } catch (Exception ex) {
            LOGGER.fine("formatDisplayDate: cannot parse '" + isoTimestamp + "' — " + ex.getMessage());
            return DISPLAY_EMPTY;
        }
    }

    /**
     * Parses an ISO date string from the batch creation timestamp column
     * ("2026-06-24" or "2026-06-24T18:07:32") into a java.util.Date so it can
     * be compared against the date-range filter values from the Datebox controls.
     *
     * Returns null for null, blank, or unparseable input — the caller treats null
     * as "skip the date filter for this row".
     */
    private Date parseDateString(String isoDateOrTimestamp) {
        if (isoDateOrTimestamp == null || isoDateOrTimestamp.isBlank()
                || DISPLAY_EMPTY.equals(isoDateOrTimestamp))
            return null;
        try {
            String datePart = isoDateOrTimestamp.contains("T")
                    ? isoDateOrTimestamp.substring(0, 10)
                    : isoDateOrTimestamp.trim();
            return toUtilDate(java.time.LocalDate.parse(datePart));
        } catch (Exception ex) {
            LOGGER.fine("parseDateString: cannot parse '" + isoDateOrTimestamp
                    + "' — " + ex.getMessage());
            return null;
        }
    }

    /**
     * Parses a cheque date string to java.util.Date for date-range filter comparisons.
     *
     * The cheque date column may be stored in two formats:
     *   "dd/MM/yyyy" — typical Indian banking format stored historically
     *   "yyyy-MM-dd" — ISO format stored by newer code
     *
     * Both formats are tried automatically based on whether the string contains "/".
     * Returns null for null, blank, or unparseable input.
     */
    private Date parseChequeDate(String chequeDateString) {
        if (chequeDateString == null || chequeDateString.isBlank()
                || DISPLAY_EMPTY.equals(chequeDateString))
            return null;
        try {
            java.time.format.DateTimeFormatter formatter = chequeDateString.contains("/")
                    ? java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy") // legacy format
                    : java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"); // ISO format
            return toUtilDate(java.time.LocalDate.parse(chequeDateString.trim(), formatter));
        } catch (Exception ex) {
            LOGGER.fine("parseChequeDate: cannot parse '"
                    + chequeDateString + "' — " + ex.getMessage());
            return null;
        }
    }

    /**
     * Converts a java.time.LocalDate (modern Java date) to the older java.util.Date
     * type that the ZK Datebox control produces for filter comparisons.
     *
     * The conversion uses the system's default time zone and sets the time to
     * midnight (start of day) so date comparisons work correctly.
     */
    private Date toUtilDate(java.time.LocalDate localDate) {
        return java.util.Date.from(
                localDate.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());
    }

    // ══════════════════════════════════════════════════════════════════════
    // 2. CHEQUE LIST
    // ══════════════════════════════════════════════════════════════════════

    /**
     * {@inheritDoc}
     *
     * Referred cheques are absent from this result because referring a cheque
     * flips its ver_level column from "V1" to "V2" in the database; the DAO
     * query filters on ver_level = "V1" so referred cheques naturally drop out.
     * The composer keeps them visible for the current session through
     * applyActionToInMemoryCheque().
     */
    @Override
    public List<ChequeEntity> getAllV1ChequesForBatch(String batchId) {
        List<ChequeEntity> cheques = chequeDAO.loadAllV1ChequesForBatch(batchId);
        return cheques != null ? cheques : new ArrayList<>();
    }
}

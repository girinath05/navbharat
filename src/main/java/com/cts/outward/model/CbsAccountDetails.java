/*
 * Project  : Navbharat CTS Outward
 * File     : CbsAccountDetails.java
 * Package  : com.cts.outward.model
 * Author   : Anusha M.
 * Created  : June 2026
 *
 * Change   : Removed CSS sclass String fields (accountStatusSclass,
 *             isNewAccountSclass, payeeMatchSclass) and replaced them with
 *             boolean flags (accountActive, newAccountFlag, payeeNamesMatch).
 *
 *             Reason: a model class must not contain CSS class names.
 *             CSS is a presentation detail — it belongs in the ZK Composer.
 *             The Composer reads the boolean flags and maps them to sclass
 *             strings in its own populateCbsFields() helper method.
 */
package com.cts.outward.model;

/**
 * Parsed result of a CBS (Firestore) account lookup, ready to be displayed
 * in the Verification I popup.
 *
 * <p>All String fields are non-null — the service substitutes an em-dash ("—")
 * for any value that is absent or unavailable, so the Composer can set ZK Label
 * values without null checks.
 *
 * <p>Boolean flags (accountActive, newAccountFlag, payeeNamesMatch) replace the
 * old CSS sclass String fields.  The Composer translates those booleans into
 * ZK sclass strings — keeping all presentation decisions out of this class.
 */
public class CbsAccountDetails {

    // ──────────────────────────────────────────────────────────────────────
    // LOOKUP STATE ENUM
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Describes the outcome of the CBS lookup so the Composer can decide
     * what to display without inspecting String values for emptiness.
     */
    public enum LookupState {

        /** Account number was present and Firestore returned a matching document. */
        FOUND,

        /**
         * Account number was present but Firestore found no matching document.
         * The Composer should show "Not found" to make clear a lookup was attempted
         * and explicitly failed, rather than just being skipped.
         */
        NOT_FOUND,

        /**
         * The cheque carries no account number — lookup was not attempted.
         * All display fields will be em-dash.
         */
        UNAVAILABLE
    }

    // ──────────────────────────────────────────────────────────────────────
    // FIELDS
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Describes how the lookup ended — used by the Composer to decide
     * which sclass to apply without comparing String field values.
     */
    private final LookupState lookupState;

    /**
     * Full name of the CBS account holder, or "—" when the lookup did not
     * return a name.
     */
    private final String accountHolderName;

    /**
     * Human-readable account status label:
     * "Active", "Inactive", "Not found", or "—".
     * The Composer uses {@link #accountActive} to pick the CSS class.
     */
    private final String accountStatus;

    /**
     * True when the CBS account exists and is currently active.
     * False for inactive accounts, NOT_FOUND results, and UNAVAILABLE results.
     *
     * <p>The Composer maps this flag to a CSS sclass:
     * {@code true} → "ch-active" | {@code false} → "ch-inactive" or "ch-cbs-unknown"
     */
    private final boolean accountActive;

    /**
     * Human-readable new-account label: "Yes", "No", or "—".
     * The Composer uses {@link #newAccountFlag} to pick the CSS class.
     */
    private final String isNewAccount;

    /**
     * True when CBS reports that the account was opened recently ("new account").
     *
     * <p>The Composer maps this flag to a CSS sclass:
     * {@code true} → "ch-new-acc" | {@code false} → "ch-not-new" or "ch-cbs-unknown"
     */
    private final boolean newAccountFlag;

    /**
     * Human-readable payee name match label: "Match", "Mismatch", or "—".
     * Compares the CBS account holder name to the payee name printed on the cheque.
     * The Composer uses {@link #payeeNamesMatch} to pick the CSS class.
     */
    private final String payeeMatchLabel;

    /**
     * True when the CBS account holder name matches the payee name on the cheque
     * (case-insensitive trim comparison).
     *
     * <p>The Composer maps this flag to a CSS sclass:
     * {@code true} → "cbs-match-ok" | {@code false} → "cbs-match-fail" or ""
     */
    private final boolean payeeNamesMatch;

    // ──────────────────────────────────────────────────────────────────────
    // CONSTRUCTOR
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Creates a fully populated CBS account details result.
     *
     * <p>Callers (i.e. {@code VerificationOneServiceImpl}) are expected to pass
     * non-null Strings for all String parameters, substituting "—" where a real
     * value is not available.
     *
     * @param lookupState       outcome of the CBS lookup (FOUND / NOT_FOUND / UNAVAILABLE)
     * @param accountHolderName CBS account holder name, or "—"
     * @param accountStatus     display label: "Active", "Inactive", "Not found", or "—"
     * @param accountActive     true when the account exists and is active in CBS
     * @param isNewAccount      display label: "Yes", "No", or "—"
     * @param newAccountFlag    true when CBS reports the account as newly opened
     * @param payeeMatchLabel   display label: "Match", "Mismatch", or "—"
     * @param payeeNamesMatch   true when CBS holder name matches the cheque payee name
     */
    public CbsAccountDetails(
            LookupState lookupState,
            String      accountHolderName,
            String      accountStatus,
            boolean     accountActive,
            String      isNewAccount,
            boolean     newAccountFlag,
            String      payeeMatchLabel,
            boolean     payeeNamesMatch) {

        this.lookupState       = lookupState;
        this.accountHolderName = accountHolderName;
        this.accountStatus     = accountStatus;
        this.accountActive     = accountActive;
        this.isNewAccount      = isNewAccount;
        this.newAccountFlag    = newAccountFlag;
        this.payeeMatchLabel   = payeeMatchLabel;
        this.payeeNamesMatch   = payeeNamesMatch;
    }

    // ──────────────────────────────────────────────────────────────────────
    // GETTERS
    // ──────────────────────────────────────────────────────────────────────

    /** @return FOUND, NOT_FOUND, or UNAVAILABLE — describes how the CBS lookup ended. */
    public LookupState getLookupState()       { return lookupState;       }

    /** @return CBS account holder name, or "—" when unavailable. */
    public String      getAccountHolderName() { return accountHolderName; }

    /** @return Display label: "Active", "Inactive", "Not found", or "—". */
    public String      getAccountStatus()     { return accountStatus;     }

    /**
     * @return true when the account is active in CBS.
     *         The Composer uses this to select the CSS sclass for the status label.
     */
    public boolean     isAccountActive()      { return accountActive;     }

    /** @return Display label: "Yes", "No", or "—". */
    public String      getIsNewAccount()      { return isNewAccount;      }

    /**
     * @return true when CBS reports the account as newly opened.
     *         The Composer uses this to select the CSS sclass for the new-account label.
     */
    public boolean     isNewAccountFlag()     { return newAccountFlag;    }

    /** @return Display label: "Match", "Mismatch", or "—". */
    public String      getPayeeMatchLabel()   { return payeeMatchLabel;   }

    /**
     * @return true when CBS holder name matches the cheque payee name (case-insensitive).
     *         The Composer uses this to select the CSS sclass for the payee-match label.
     */
    public boolean     isPayeeNamesMatch()    { return payeeNamesMatch;   }
}
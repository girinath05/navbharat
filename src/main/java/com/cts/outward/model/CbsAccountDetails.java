/*
 * Project  : Navbharat CTS Outward
 * File     : CbsAccountDetails.java
 * Package  : com.cts.outward.model
 * Author   : Anusha M.
 * Created  : June 2026
 */
package com.cts.outward.model;

/**
 * Parsed result of a CBS (Firestore) account lookup, ready for direct display
 * in the verification popup.
 *
 * <p>All fields are non-null — the service substitutes an em-dash ("—") for any
 * value that is absent or unavailable, so the Composer can set label values
 * without null checks.
 */
public class CbsAccountDetails {

    /** One of three states describing the outcome of the lookup. */
    public enum LookupState {
        /** Account number was present and Firestore returned a document. */
        FOUND,
        /** Account number was present but Firestore found no matching document. */
        NOT_FOUND,
        /** The cheque carries no account number — lookup was not attempted. */
        UNAVAILABLE
    }

    private final LookupState lookupState;

    /** CBS account holder name, or "—" when unavailable. */
    private final String  accountHolderName;

    /** "Active", "Inactive", "Not found", or "—" depending on lookupState. */
    private final String  accountStatus;

    /** CSS class to apply to the account-status label. */
    private final String  accountStatusSclass;

    /** "Yes", "No", or "—". */
    private final String  isNewAccount;

    /** CSS class to apply to the new-account label. */
    private final String  isNewAccountSclass;

    /**
     * "Match", "Mismatch", or "—".
     * Compares CBS accountHolderName to the payee name on the cheque.
     */
    private final String  payeeMatchLabel;

    /** CSS class to apply to the payee-match label. */
    private final String  payeeMatchSclass;

    /** True only when lookupState is FOUND and the account is active in CBS. */
    private final boolean isActive;

    public CbsAccountDetails(LookupState lookupState,
                             String accountHolderName,
                             String accountStatus,
                             String accountStatusSclass,
                             String isNewAccount,
                             String isNewAccountSclass,
                             String payeeMatchLabel,
                             String payeeMatchSclass,
                             boolean isActive) {
        this.lookupState         = lookupState;
        this.accountHolderName   = accountHolderName;
        this.accountStatus       = accountStatus;
        this.accountStatusSclass = accountStatusSclass;
        this.isNewAccount        = isNewAccount;
        this.isNewAccountSclass  = isNewAccountSclass;
        this.payeeMatchLabel     = payeeMatchLabel;
        this.payeeMatchSclass    = payeeMatchSclass;
        this.isActive            = isActive;
    }

    public LookupState getLookupState()         { return lookupState;         }
    public String      getAccountHolderName()   { return accountHolderName;   }
    public String      getAccountStatus()       { return accountStatus;       }
    public String      getAccountStatusSclass() { return accountStatusSclass; }
    public String      getIsNewAccount()        { return isNewAccount;        }
    public String      getIsNewAccountSclass()  { return isNewAccountSclass;  }
    public String      getPayeeMatchLabel()     { return payeeMatchLabel;     }
    public String      getPayeeMatchSclass()    { return payeeMatchSclass;    }
    public boolean     isActive()               { return isActive;            }
}
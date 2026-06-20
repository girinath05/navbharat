/*
 * ============================================================
 *  Project : Navbharat CTS Outward
 *  File    : OutwardDashboardStats.java
 *  Package : com.cts.outward.model
 *  Desc    : Value object holding the 4 stat-card counts for
 *            the Outward Dashboard page.
 *            Populated by BatchDAOImpl.getDashboardStats().
 *
 *  Card mapping:
 *    totalBatches        → ALL batches today (any status)
 *    verificationBatches → ReadyForVerification + VerificationInProgress
 *    verifiedBatches     → Verified  (all cheques verified, batch done)
 *    dispatchedBatches   → CxfGenerated + Dispatched
 * ============================================================
 */
package com.cts.outward.model;

public class OutwardDashboardStats {

    private int totalBatches;
    private int verificationBatches;  // Card 2 — in verification stage
    private int verifiedBatches;      // Card 3 — all cheques verified
    private int dispatchedBatches;    // Card 4 — CXF generated / dispatched

    public OutwardDashboardStats() {}

    public OutwardDashboardStats(int total, int verification, int verified, int dispatched) {
        this.totalBatches        = total;
        this.verificationBatches = verification;
        this.verifiedBatches     = verified;
        this.dispatchedBatches   = dispatched;
    }

    // ── Getters ──────────────────────────────────────────────
    public int getTotalBatches()        { return totalBatches;        }
    public int getVerificationBatches() { return verificationBatches; }
    public int getVerifiedBatches()     { return verifiedBatches;     }
    public int getDispatchedBatches()   { return dispatchedBatches;   }

    // ── Setters ──────────────────────────────────────────────
    public void setTotalBatches(int v)        { this.totalBatches        = v; }
    public void setVerificationBatches(int v) { this.verificationBatches = v; }
    public void setVerifiedBatches(int v)     { this.verifiedBatches     = v; }
    public void setDispatchedBatches(int v)   { this.dispatchedBatches   = v; }

    // ── Legacy getters — keep until all callers updated ──────
    /** @deprecated use getVerifiedBatches() */
    public int getCxfGenerating()  { return verifiedBatches;   }
    /** @deprecated use getDispatchedBatches() */
    public int getSentToNpci()     { return dispatchedBatches; }
}
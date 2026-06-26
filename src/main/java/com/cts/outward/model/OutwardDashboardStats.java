/*
 * ============================================================
 *  Project : Navbharat CTS Outward
 *  File    : OutwardDashboardStats.java
 *  Package : com.cts.outward.model
 *  Desc    : Value object holding the 4 stat-card counts for
 *            the Outward Dashboard page.
 *            Populated by OutwardDashboardDAOImpl.getDashboardStats().
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

    // ── Stat-card fields — each maps to one dashboard card count ─────────────
    private int totalBatches;
    private int verificationBatches;  // Card 2 — in verification stage (ReadyForVerification + VerificationInProgress)
    private int verifiedBatches;      // Card 3 — all cheques verified, batch is fully done
    private int dispatchedBatches;    // Card 4 — CXF generated or dispatched to NPCI

    // ── No-arg constructor required for frameworks (Hibernate, ZK binding) ───
    public OutwardDashboardStats() {}

    // ── All-args constructor — used by OutwardDashboardDAOImpl.getDashboardStats() ──
    public OutwardDashboardStats(int totalBatches,
                                  int verificationBatches,
                                  int verifiedBatches,
                                  int dispatchedBatches) {
        this.totalBatches        = totalBatches;
        this.verificationBatches = verificationBatches;
        this.verifiedBatches     = verifiedBatches;
        this.dispatchedBatches   = dispatchedBatches;
    }

    // ── Getters ──────────────────────────────────────────────
    public int getTotalBatches()        { return totalBatches;        }
    public int getVerificationBatches() { return verificationBatches; }
    public int getVerifiedBatches()     { return verifiedBatches;     }
    public int getDispatchedBatches()   { return dispatchedBatches;   }

    // ── Setters ──────────────────────────────────────────────
    public void setTotalBatches(int totalBatches)               { this.totalBatches        = totalBatches;        }
    public void setVerificationBatches(int verificationBatches) { this.verificationBatches = verificationBatches; }
    public void setVerifiedBatches(int verifiedBatches)         { this.verifiedBatches     = verifiedBatches;     }
    public void setDispatchedBatches(int dispatchedBatches)     { this.dispatchedBatches   = dispatchedBatches;   }

    // ── Legacy getters — keep until all callers updated ──────
    // Retained for backward compatibility; delegates to the renamed field getter.
    /** @deprecated use getVerifiedBatches() */
    public int getCxfGenerating()  { return verifiedBatches;   }
    // Retained for backward compatibility; delegates to the renamed field getter.
    /** @deprecated use getDispatchedBatches() */
    public int getSentToNpci()     { return dispatchedBatches; }
}
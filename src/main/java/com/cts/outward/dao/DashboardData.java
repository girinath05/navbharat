/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : DashboardData.java
 *  Package     : com.cts.outward.dao
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : Immutable value object returned by
 *                ChequeDAO.loadDashboardData(). Carries the
 *                recent batch list and the pending-cheque count
 *                needed to populate the outward dashboard stat
 *                cards in a single DAO round-trip.
 * ============================================================
 */

package com.cts.outward.dao;

import java.util.List;

import com.cts.outward.entity.BatchEntity;

public class DashboardData {

	public final List<BatchEntity> batches;
	public final long pendingCount;

	public DashboardData(List<BatchEntity> batches, long pendingCount) {
		this.batches = batches;
		this.pendingCount = pendingCount;
	}
}
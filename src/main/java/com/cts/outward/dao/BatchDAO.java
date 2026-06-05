/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : BatchDAO.java
 *  Package     : com.cts.outward.dao
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : DAO interface for batch-level persistence.
 *                Declares CRUD and status-update contracts for
 *                BatchEntity. Implemented by BatchDAOImpl using
 *                Hibernate sessions from HibernateUtil.
 * ============================================================
 */

// BatchDAO.java
package com.cts.outward.dao;

import java.math.BigDecimal;
import java.util.List;

import com.cts.outward.entity.BatchEntity;

public interface BatchDAO {

	int loadMaxBatchSeq();

	void saveBatch(BatchEntity batch);

	void deleteBatchAndCheques(String batchId);

	List<BatchEntity> loadAllBatches();

	void updateBatchStatus(String batchId, String status);

	BatchEntity getBatchById(String batchId);

	void updateBatch(BatchEntity batch);

	BatchEntity findBatchById(String batchId);

	void updateBatchActualCounts(String batchId, int actualCheques, BigDecimal actualAmount, String status);

	/** Batches that have at least one high_value cheque */
	List<BatchEntity> loadBatchesWithHvCheques();
}
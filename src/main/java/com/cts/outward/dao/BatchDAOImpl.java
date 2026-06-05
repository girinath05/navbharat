/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : BatchDAOImpl.java
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : Hibernate-based implementation of BatchDAO.
 *                All write operations open an explicit transaction,
 *                roll back on any exception, and re-throw as
 *                RuntimeException to allow service-layer handling.
 *                Read operations use auto-close sessions with no
 *                explicit transaction (read-only intent).
 * ============================================================
 */

package com.cts.outward.dao;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import org.hibernate.Session;
import org.hibernate.Transaction;

import com.cts.outward.entity.BatchEntity;
import com.cts.outward.util.HibernateUtil;

public class BatchDAOImpl implements BatchDAO {

	private static final Logger LOG = Logger.getLogger(BatchDAOImpl.class.getName());

	@Override
	public int loadMaxBatchSeq() {
		try (Session session = HibernateUtil.getSession()) {
			String result = session
					.createNativeQuery(
							"SELECT batch_id FROM cts_batches " + "WHERE batch_id ~ '^BATCH[0-9]+$' "
									+ "ORDER BY CAST(SUBSTRING(batch_id FROM 6) AS INTEGER) DESC " + "LIMIT 1",
							String.class)
					.uniqueResult();
			if (result == null)
				return 100;
			return Integer.parseInt(result.substring(5)) + 1;
		} catch (Exception ex) {
			LOG.warning("loadMaxBatchSeq: " + ex.getMessage());
			return 100;
		}
	}

	@Override
	public void saveBatch(BatchEntity batch) {
		Transaction tx = null;
		try (Session session = HibernateUtil.getSession()) {
			tx = session.beginTransaction();
			session.merge(batch);
			tx.commit();
			LOG.info("Batch saved: " + batch.getBatchId());
		} catch (Exception ex) {
			if (tx != null)
				tx.rollback();
			LOG.severe("saveBatch error: " + ex.getMessage());
			throw new RuntimeException("Failed to save batch: " + ex.getMessage(), ex);
		}
	}

	@Override
	public void deleteBatchAndCheques(String batchId) {
		Transaction tx = null;
		try (Session session = HibernateUtil.getSession()) {
			tx = session.beginTransaction();
			int chequesDel = session.createMutationQuery("DELETE FROM ChequeEntity c WHERE c.batchId = :batchId")
					.setParameter("batchId", batchId).executeUpdate();
			int batchDel = session.createMutationQuery("DELETE FROM BatchEntity b WHERE b.batchId = :batchId")
					.setParameter("batchId", batchId).executeUpdate();
			tx.commit();
			LOG.info(
					"Discarded batch " + batchId + ": " + batchDel + " batch row, " + chequesDel + " cheques removed.");
		} catch (Exception ex) {
			if (tx != null)
				tx.rollback();
			LOG.severe("deleteBatchAndCheques error: " + ex.getMessage());
			throw new RuntimeException("Failed to discard batch: " + ex.getMessage(), ex);
		}
	}

	@Override
	public List<BatchEntity> loadAllBatches() {
		try (Session session = HibernateUtil.getSession()) {
			return session.createQuery("FROM BatchEntity ORDER BY createdAt DESC", BatchEntity.class).list();
		} catch (Exception ex) {
			LOG.severe("loadAllBatches error: " + ex.getMessage());
			return Collections.emptyList();
		}
	}

	@Override
	public void updateBatchStatus(String batchId, String status) {
		Transaction tx = null;
		try (Session session = HibernateUtil.getSession()) {
			tx = session.beginTransaction();
			session.createMutationQuery("UPDATE BatchEntity SET status = :status, updatedAt = CURRENT_TIMESTAMP "
					+ "WHERE batchId = :batchId").setParameter("status", status).setParameter("batchId", batchId)
					.executeUpdate();
			tx.commit();
		} catch (Exception ex) {
			if (tx != null)
				tx.rollback();
			LOG.severe("updateBatchStatus error: " + ex.getMessage());
		}
	}

	@Override
	public void updateBatch(BatchEntity batch) {
		Transaction tx = null;
		try (Session session = HibernateUtil.getSession()) {
			tx = session.beginTransaction();
			session.createMutationQuery("UPDATE BatchEntity SET " + "  branchCode     = :branchCode, "
					+ "  totalCheques   = :totalCheques, " + "  totalAmount    = :totalAmount, "
					+ "  controlAmount  = :controlAmount, " + "  batchType      = :batchType, "
					+ "  status         = :status, " + "  updatedAt      = CURRENT_TIMESTAMP "
					+ "WHERE batchId = :batchId").setParameter("branchCode", batch.getBranchCode())
					.setParameter("totalCheques", batch.getTotalCheques())
					.setParameter("totalAmount", batch.getTotalAmount())
					.setParameter("controlAmount", batch.getControlAmount())
					.setParameter("batchType", batch.getBatchType())
					.setParameter("status", batch.getStatus() != null ? batch.getStatus() : "Pending")
					.setParameter("batchId", batch.getBatchId()).executeUpdate();
			tx.commit();
			LOG.info("Batch updated: " + batch.getBatchId());
		} catch (Exception ex) {
			if (tx != null)
				tx.rollback();
			LOG.severe("updateBatch error: " + ex.getMessage());
			throw new RuntimeException("updateBatch failed: " + ex.getMessage(), ex);
		}
	}

	@Override
	public void updateBatchActualCounts(String batchId, int actualCheques, BigDecimal actualAmount, String status) {
		Transaction tx = null;
		try (Session session = HibernateUtil.getSession()) {
			tx = session.beginTransaction();
			session.createMutationQuery("UPDATE BatchEntity SET " + "  totalCheques = :tc, " + "  totalAmount  = :ta, "
					+ "  status       = :st, " + "  updatedAt    = CURRENT_TIMESTAMP " + "WHERE batchId  = :bid")
					.setParameter("tc", actualCheques).setParameter("ta", actualAmount).setParameter("st", status)
					.setParameter("bid", batchId).executeUpdate();
			tx.commit();
			LOG.info("Batch " + batchId + " updated: cheques=" + actualCheques + " amt=" + actualAmount);
		} catch (Exception ex) {
			if (tx != null)
				tx.rollback();
			LOG.severe("updateBatchActualCounts error: " + ex.getMessage());
			throw new RuntimeException("updateBatchActualCounts failed: " + ex.getMessage(), ex);
		}
	}

	@Override
	public List<BatchEntity> loadBatchesWithHvCheques() {
		try (Session session = HibernateUtil.getSession()) {
			return session.createQuery(
					"FROM BatchEntity b WHERE EXISTS (" + "  SELECT 1 FROM ChequeEntity c "
							+ "  WHERE c.batchId = b.batchId AND c.highValue = true" + ") ORDER BY b.createdAt DESC",
					BatchEntity.class).list();
		} catch (Exception ex) {
			LOG.severe("loadBatchesWithHvCheques error: " + ex.getMessage());
			return Collections.emptyList();
		}
	}

	// BatchDAOImpl
	@Override
	public BatchEntity findBatchById(String batchId) {
		try (Session session = HibernateUtil.getSession()) {
			return session.createQuery("FROM BatchEntity WHERE batchId = :batchId", BatchEntity.class)
					.setParameter("batchId", batchId).uniqueResult();
		} catch (Exception ex) {
			LOG.severe("findBatchById error: " + ex.getMessage());
			return null;
		}
	}

	@Override
	public BatchEntity getBatchById(String batchId) {
		// TODO Auto-generated method stub
		return null;
	}
}
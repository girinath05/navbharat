/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : ChequeDAOImpl.java
 *  Package     : com.cts.outward.dao
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : Hibernate-based implementation of ChequeDAO.
 *                Write methods use explicit transactions with
 *                rollback-on-exception. Read methods are
 *                sessionless (auto-close, no tx). HQL projection
 *                queries intentionally exclude BLOB columns for
 *                list views to reduce memory overhead.
 * ============================================================
 */


package com.cts.outward.dao;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.query.Query;

import com.cts.outward.entity.BatchEntity;
import com.cts.outward.entity.ChequeEntity;
import com.cts.outward.util.HibernateUtil;

public class ChequeDAOImpl implements ChequeDAO {

	private static final Logger LOG = Logger.getLogger(ChequeDAOImpl.class.getName());

	// ══════════════════════════════════════════════════════════
	// DASHBOARD
	// ══════════════════════════════════════════════════════════

	@Override
	public DashboardData loadDashboardData() {
		try (Session session = HibernateUtil.getSession()) {
			List<BatchEntity> batches = session
					.createQuery("FROM BatchEntity ORDER BY createdAt DESC", BatchEntity.class).list();
			Long pending = session
					.createQuery("SELECT COUNT(c) FROM ChequeEntity c WHERE c.verStatus = 'Pending'", Long.class)
					.uniqueResult();
			return new DashboardData(batches, pending != null ? pending : 0L);
		} catch (Exception ex) {
			LOG.severe("loadDashboardData error: " + ex.getMessage());
			return new DashboardData(Collections.emptyList(), 0L);
		}
	}

	// ══════════════════════════════════════════════════════════
	// CHEQUE OPERATIONS
	// ══════════════════════════════════════════════════════════

	@Override
	public void saveCheque(ChequeEntity cheque) {
		Transaction tx = null;
		try (Session session = HibernateUtil.getSession()) {
			tx = session.beginTransaction();
			if (cheque.getId() == null) {
				session.persist(cheque);
			} else {
				session.merge(cheque);
			}
			tx.commit();
		} catch (Exception ex) {
			if (tx != null)
				tx.rollback();
			LOG.severe("saveCheque error: " + ex.getMessage());
			throw new RuntimeException("Failed to save cheque: " + ex.getMessage(), ex);
		}
	}

	@Override
	public void saveCheques(List<ChequeEntity> cheques) {
		if (cheques == null || cheques.isEmpty())
			return;
		Transaction tx = null;
		try (Session session = HibernateUtil.getSession()) {
			tx = session.beginTransaction();
			int count = 0;
			for (ChequeEntity c : cheques) {
				session.persist(c);
				count++;
				if (count % 50 == 0) {
					session.flush();
					session.clear();
				}
			}
			session.flush();
			tx.commit();
			LOG.info("Saved " + cheques.size() + " cheques to Supabase");
		} catch (Exception ex) {
			if (tx != null)
				tx.rollback();
			LOG.severe("saveCheques error: " + ex.getMessage());
			throw new RuntimeException("Failed to save cheques: " + ex.getMessage(), ex);
		}
	}

	@Override
	public List<ChequeEntity> loadChequesForBatch(String batchId) {
		try (Session session = HibernateUtil.getSession()) {
			Query<ChequeEntity> q = session.createQuery("SELECT new com.cts.outward.entity.ChequeEntity("
					+ "  c.id, c.batchId, c.chequeId, c.chequeNo, c.accountNo, "
					+ "  c.sortCode, c.amount, c.chequeDate, c.drawerName, c.payeeName, "
					+ "  c.iqaStatus, c.verStatus, c.status, c.highValue, c.duplicate, " + "  c.createdAt, c.updatedAt"
					+ ") FROM ChequeEntity c WHERE c.batchId = :batchId ORDER BY c.id", ChequeEntity.class);
			q.setParameter("batchId", batchId);
			return q.list();
		} catch (Exception ex) {
			LOG.warning("loadChequesForBatch projection failed, falling back: " + ex.getMessage());
			return loadChequesForBatchFull(batchId);
		}
	}

	@Override
	public List<ChequeEntity> loadChequesForBatchFull(String batchId) {
		try (Session session = HibernateUtil.getSession()) {
			Query<ChequeEntity> q = session.createQuery("FROM ChequeEntity WHERE batchId = :batchId ORDER BY id",
					ChequeEntity.class);
			q.setParameter("batchId", batchId);
			return q.list();
		} catch (Exception ex) {
			LOG.severe("loadChequesForBatchFull error: " + ex.getMessage());
			return Collections.emptyList();
		}
	}

	@Override
	public ChequeEntity loadChequeWithImages(Long chequeId) {
		try (Session session = HibernateUtil.getSession()) {
			return session.get(ChequeEntity.class, chequeId);
		} catch (Exception ex) {
			LOG.severe("loadChequeWithImages error: " + ex.getMessage());
			return null;
		}
	}

	@Override
	public void updateChequeStatus(Long chequeId, String status, String verStatus) {
		Transaction tx = null;
		try (Session session = HibernateUtil.getSession()) {
			tx = session.beginTransaction();
			session.createMutationQuery("UPDATE ChequeEntity " + "SET status = :status, verStatus = :verStatus, "
					+ "    updatedAt = CURRENT_TIMESTAMP " + "WHERE id = :id").setParameter("status", status)
					.setParameter("verStatus", verStatus).setParameter("id", chequeId).executeUpdate();
			tx.commit();
		} catch (Exception ex) {
			if (tx != null)
				tx.rollback();
			LOG.severe("updateChequeStatus error: " + ex.getMessage());
		}
	}

	@Override
	public void deleteCheque(Long chequeId) {
		Transaction tx = null;
		try (Session session = HibernateUtil.getSession()) {
			tx = session.beginTransaction();
			session.createMutationQuery("DELETE FROM ChequeEntity c WHERE c.id = :id").setParameter("id", chequeId)
					.executeUpdate();
			tx.commit();
			LOG.info("Cheque deleted: id=" + chequeId);
		} catch (Exception ex) {
			if (tx != null)
				tx.rollback();
			LOG.severe("deleteCheque error: " + ex.getMessage());
			throw new RuntimeException("deleteCheque failed: " + ex.getMessage(), ex);
		}
	}

	@Override
	public long countPendingCheques() {
		try (Session session = HibernateUtil.getSession()) {
			Long result = session
					.createQuery("SELECT COUNT(c) FROM ChequeEntity c WHERE c.verStatus = 'Pending'", Long.class)
					.uniqueResult();
			return result != null ? result : 0L;
		} catch (Exception ex) {
			LOG.severe("countPendingCheques error: " + ex.getMessage());
			return 0;
		}
	}

	// ══════════════════════════════════════════════════════════
	// MICR REPAIR OPERATIONS
	// ══════════════════════════════════════════════════════════

	@Override
	public List<ChequeEntity> loadIqaFailedCheques(String batchId) {
		try (Session session = HibernateUtil.getSession()) {
			return session.createQuery(
					"SELECT new com.cts.outward.entity.ChequeEntity("
							+ "  c.id, c.batchId, c.chequeId, c.chequeNo, c.accountNo, "
							+ "  c.sortCode, c.amount, c.chequeDate, c.drawerName, c.payeeName, "
							+ "  c.iqaStatus, c.verStatus, c.status, c.highValue, c.duplicate, "
							+ "  c.createdAt, c.updatedAt" + ") FROM ChequeEntity c " + "WHERE c.batchId = :batchId "
							+ "  AND c.iqaStatus = 'Fail' " + "  AND c.status = 'MICR_Repair' " + "ORDER BY c.id",
					ChequeEntity.class).setParameter("batchId", batchId).list();
		} catch (Exception ex) {
			LOG.severe("loadIqaFailedCheques error: " + ex.getMessage());
			return Collections.emptyList();
		}
	}

	@Override
	public void updateChequeFields(ChequeEntity cheque) {
		Transaction tx = null;
		try (Session session = HibernateUtil.getSession()) {
			tx = session.beginTransaction();
			session.createMutationQuery("UPDATE ChequeEntity SET " + "  sortCode        = :sortCode, "
					+ "  transactionCode = :txCode, " + "  accountNo       = :accountNo, "
					+ "  amount          = :amount, " + "  chequeDate      = :chequeDate, "
					+ "  payeeName       = :payeeName, " + "  status          = :status, "
					+ "  updatedAt       = CURRENT_TIMESTAMP " + "WHERE id = :id")
					.setParameter("sortCode", cheque.getSortCode()).setParameter("txCode", cheque.getTransactionCode())
					.setParameter("accountNo", cheque.getAccountNo()).setParameter("amount", cheque.getAmount())
					.setParameter("chequeDate", cheque.getChequeDate()).setParameter("payeeName", cheque.getPayeeName())
					.setParameter("status", cheque.getStatus()).setParameter("id", cheque.getId()).executeUpdate();
			tx.commit();
			LOG.info("MICR fields updated for cheque id=" + cheque.getId());
		} catch (Exception ex) {
			if (tx != null)
				tx.rollback();
			LOG.severe("updateChequeFields error: " + ex.getMessage());
			throw new RuntimeException("Failed to update cheque fields: " + ex.getMessage(), ex);
		}
	}

	@Override
	public long countRepairedToday() {
		try (Session session = HibernateUtil.getSession()) {
			Long result = session.createQuery("SELECT COUNT(c) FROM ChequeEntity c "
					+ "WHERE c.status = 'Sent_for_Verification' " + "  AND c.updatedAt >= CURRENT_DATE", Long.class)
					.uniqueResult();
			return result != null ? result : 0L;
		} catch (Exception ex) {
			LOG.severe("countRepairedToday error: " + ex.getMessage());
			return 0L;
		}
	}

	// ══════════════════════════════════════════════════════════
	// HIGH VALUE (HV) OPERATIONS
	// ══════════════════════════════════════════════════════════

	@Override
	public List<ChequeEntity> loadHvChequesForBatch(String batchId) {
		try (Session session = HibernateUtil.getSession()) {
			return session.createQuery("SELECT new com.cts.outward.entity.ChequeEntity("
					+ "  c.id, c.batchId, c.chequeId, c.chequeNo, c.accountNo, "
					+ "  c.sortCode, c.amount, c.chequeDate, c.drawerName, c.payeeName, "
					+ "  c.iqaStatus, c.verStatus, c.status, c.highValue, c.duplicate, " + "  c.createdAt, c.updatedAt"
					+ ") FROM ChequeEntity c " + "WHERE c.batchId = :batchId AND c.highValue = true " + "ORDER BY c.id",
					ChequeEntity.class).setParameter("batchId", batchId).list();
		} catch (Exception ex) {
			LOG.warning("loadHvChequesForBatch projection failed, fallback: " + ex.getMessage());
			try (Session session = HibernateUtil.getSession()) {
				return session.createQuery(
						"FROM ChequeEntity c " + "WHERE c.batchId = :batchId AND c.highValue = true " + "ORDER BY c.id",
						ChequeEntity.class).setParameter("batchId", batchId).list();
			} catch (Exception ex2) {
				LOG.severe("loadHvChequesForBatch fallback error: " + ex2.getMessage());
				return Collections.emptyList();
			}
		}
	}

	@Override
	public long countHvPendingCheques() {
		try (Session session = HibernateUtil.getSession()) {
			Long result = session.createQuery(
					"SELECT COUNT(c) FROM ChequeEntity c " + "WHERE c.highValue = true AND c.verStatus = 'Pending'",
					Long.class).uniqueResult();
			return result != null ? result : 0L;
		} catch (Exception ex) {
			LOG.severe("countHvPendingCheques error: " + ex.getMessage());
			return 0L;
		}
	}

	@Override
	public long countHvByVerStatus(String verStatus) {
		try (Session session = HibernateUtil.getSession()) {
			Long result = session.createQuery(
					"SELECT COUNT(c) FROM ChequeEntity c " + "WHERE c.highValue = true AND c.verStatus = :vs",
					Long.class).setParameter("vs", verStatus).uniqueResult();
			return result != null ? result : 0L;
		} catch (Exception ex) {
			LOG.severe("countHvByVerStatus error: " + ex.getMessage());
			return 0L;
		}
	}

	@Override
	public long countHvPendingForBatch(String batchId) {
		try (Session session = HibernateUtil.getSession()) {
			Long result = session
					.createQuery("SELECT COUNT(c) FROM ChequeEntity c " + "WHERE c.batchId = :batchId "
							+ "  AND c.highValue = true " + "  AND c.verStatus = 'Pending'", Long.class)
					.setParameter("batchId", batchId).uniqueResult();
			return result != null ? result : 0L;
		} catch (Exception ex) {
			LOG.severe("countHvPendingForBatch error: " + ex.getMessage());
			return 0L;
		}
	}

	@Override
	public BigDecimal sumHvPendingAmount() {
		try (Session session = HibernateUtil.getSession()) {
			BigDecimal result = session.createQuery("SELECT SUM(c.amount) FROM ChequeEntity c "
					+ "WHERE c.highValue = true AND c.verStatus = 'Pending'", BigDecimal.class).uniqueResult();
			return result != null ? result : BigDecimal.ZERO;
		} catch (Exception ex) {
			LOG.severe("sumHvPendingAmount error: " + ex.getMessage());
			return BigDecimal.ZERO;
		}
	}

	// ══════════════════════════════════════════════════════════
	// CXF GENERATION
	// ══════════════════════════════════════════════════════════

	@Override
	public List<ChequeEntity> loadAcceptedInstrumentsForCxf() {
		try (Session session = HibernateUtil.getSession()) {
			List<ChequeEntity> results = session.createQuery("SELECT new com.cts.outward.entity.ChequeEntity("
					+ "  c.id, c.batchId, c.chequeId, c.chequeNo, c.accountNo, "
					+ "  c.sortCode, c.amount, c.chequeDate, c.drawerName, c.payeeName, "
					+ "  c.iqaStatus, c.verStatus, c.status, c.highValue, c.duplicate, " + "  c.createdAt, c.updatedAt"
					+ ") FROM ChequeEntity c " + "WHERE c.verStatus = 'Accepted' "
					+ "  AND (c.status IS NULL OR c.status <> 'CXF_Generated') " + "ORDER BY c.batchId ASC, c.id ASC",
					ChequeEntity.class).list();

			if (results.isEmpty())
				return results;

			List<Long> ids = results.stream().map(ChequeEntity::getId).toList();
			List<Object[]> tcRows = session
					.createQuery("SELECT c.id, c.transactionCode FROM ChequeEntity c WHERE c.id IN :ids",
							Object[].class)
					.setParameter("ids", ids).list();

			Map<Long, String> tcMap = new HashMap<>();
			for (Object[] row : tcRows) {
				tcMap.put((Long) row[0], (String) row[1]);
			}
			results.forEach(c -> c.setTransactionCode(tcMap.get(c.getId())));

			LOG.info("loadAcceptedInstrumentsForCxf: " + results.size() + " instruments ready.");
			return results;

		} catch (Exception ex) {
			LOG.severe("loadAcceptedInstrumentsForCxf error: " + ex.getMessage());
			return Collections.emptyList();
		}
	}
}
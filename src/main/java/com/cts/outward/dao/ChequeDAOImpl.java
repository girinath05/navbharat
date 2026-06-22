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
 *                sessionless (auto-close, no tx).
 *
 *                NOTE: All queries are native PostgreSQL SQL
 *                (session.createNativeQuery) — no HQL is used in
 *                this class. List-projection queries select only
 *                the columns needed for list views (BLOB columns
 *                front_image / rear_image are intentionally
 *                excluded) and are mapped into ChequeEntity via
 *                the existing 17-field projection constructor,
 *                with extra columns merged in via a second query
 *                exactly as before.
 * ============================================================
 */

package com.cts.outward.dao;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.query.NativeQuery;

import com.cts.outward.entity.BatchEntity;
import com.cts.outward.entity.ChequeEntity;
import com.cts.outward.util.HibernateUtil;

public class ChequeDAOImpl implements ChequeDAO {

	private static final Logger LOG = Logger.getLogger(ChequeDAOImpl.class.getName());

	// Column list for the list-view projection (excludes BLOBs + amountInWords,
	// matches the old 17-arg HQL projection constructor exactly).
	private static final String PROJECTION_COLS =
			"id, batch_id, cheque_id, cheque_no, account_no, "
			+ "sort_code, amount, cheque_date, drawer_name, payee_name, "
			+ "iqa_status, ver_status, status, high_value, duplicate_flag, "
			+ "created_at, updated_at";

	/**
	 * Maps one Object[] row (column order = PROJECTION_COLS) to a ChequeEntity
	 * using the existing 17-field projection constructor.
	 */
	private static ChequeEntity mapProjectionRow(Object[] r) {
		return new ChequeEntity(
				toLong(r[0]),                 // id
				(String) r[1],                 // batch_id
				(String) r[2],                 // cheque_id
				(String) r[3],                 // cheque_no
				(String) r[4],                 // account_no
				(String) r[5],                 // sort_code
				toBigDecimal(r[6]),             // amount
				(String) r[7],                 // cheque_date
				(String) r[8],                 // drawer_name
				(String) r[9],                 // payee_name
				(String) r[10],                // iqa_status
				(String) r[11],                // ver_status
				(String) r[12],                // status
				r[13] != null && (Boolean) r[13], // high_value
				r[14] != null && (Boolean) r[14], // duplicate_flag
				toLocalDateTime(r[15]),        // created_at
				toLocalDateTime(r[16]));        // updated_at
	}

	private static Long toLong(Object o) {
		if (o == null) return null;
		if (o instanceof Long) return (Long) o;
		return ((Number) o).longValue();
	}

	private static BigDecimal toBigDecimal(Object o) {
		if (o == null) return null;
		if (o instanceof BigDecimal) return (BigDecimal) o;
		return new BigDecimal(o.toString());
	}

	private static LocalDateTime toLocalDateTime(Object o) {
		if (o == null) return null;
		if (o instanceof LocalDateTime) return (LocalDateTime) o;
		return ((java.sql.Timestamp) o).toLocalDateTime();
	}

	@Override
	public DashboardData loadDashboardData() {
		try (Session session = HibernateUtil.getSession()) {
			List<BatchEntity> batches = session
					.createNativeQuery("SELECT * FROM cts_batches ORDER BY created_at DESC", BatchEntity.class)
					.list();
			Long pending = ((Number) session
					.createNativeQuery("SELECT COUNT(*) FROM cts_cheques WHERE ver_status = 'Pending'", Object.class)
					.uniqueResult()).longValue();
			return new DashboardData(batches, pending != null ? pending : 0L);
		} catch (Exception ex) {
			LOG.severe("loadDashboardData error: " + ex.getMessage());
			return new DashboardData(Collections.emptyList(), 0L);
		}
	}

	@Override
	public void saveCheque(ChequeEntity cheque) {
		Transaction tx = null;
		try (Session session = HibernateUtil.getSession()) {
			tx = session.beginTransaction();
			if (cheque.getId() == null)
				session.persist(cheque);
			else
				session.merge(cheque);
			tx.commit();
		} catch (Exception ex) {
			if (tx != null)
				tx.rollback();
			LOG.severe("saveCheque error: " + ex.getMessage());
			throw new RuntimeException("Failed to save cheque: " + ex.getMessage(), ex);
		}
	}

	private static final ExecutorService IMAGE_EXEC =
	        Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "img-save"); t.setDaemon(true); return t; });

	
	//SAVING IMG ON Supabase  
	// 6. SAVE CHEQUES TO DB
	
	@Override
	public void saveCheques(List<ChequeEntity> cheques) {
	    if (cheques == null || cheques.isEmpty())
	        return;

	    List<byte[]> fronts = new ArrayList<>(cheques.size());
	    List<byte[]> rears  = new ArrayList<>(cheques.size());
	    for (ChequeEntity c : cheques) {
	        fronts.add(c.getFrontImage());
	        rears.add(c.getRearImage());
	        c.setFrontImage(null);
	        c.setRearImage(null);
	    }

	    Transaction tx = null;
	    try (Session session = HibernateUtil.getSession()) {
	        tx = session.beginTransaction();
	        int count = 0;
	        for (ChequeEntity c : cheques) {
	            session.persist(c);
	            count++;
	            if (count % 50 == 0) { session.flush(); session.clear(); }
	        }
	        session.flush();
	        tx.commit();
	        LOG.info("Saved " + cheques.size() + " cheques (metadata) to Supabase");
	    } catch (Exception ex) {
	        if (tx != null) tx.rollback();
	        for (int i = 0; i < cheques.size(); i++) {
	            cheques.get(i).setFrontImage(fronts.get(i));
	            cheques.get(i).setRearImage(rears.get(i));
	        }
	        LOG.severe("saveCheques error: " + ex.getMessage());
	        throw new RuntimeException("Failed to save cheques: " + ex.getMessage(), ex);
	    }

	    for (int i = 0; i < cheques.size(); i++) {
	        cheques.get(i).setFrontImage(fronts.get(i));
	        cheques.get(i).setRearImage(rears.get(i));
	    }

	    boolean hasImages = false;
	    for (byte[] b : fronts) { if (b != null) { hasImages = true; break; } }
	    if (!hasImages) for (byte[] b : rears) { if (b != null) { hasImages = true; break; } }
	    if (!hasImages) return;

	    final List<Long>   snapIds    = new ArrayList<>(cheques.size());
	    final List<byte[]> snapFronts = new ArrayList<>(fronts);
	    final List<byte[]> snapRears  = new ArrayList<>(rears);
	    for (ChequeEntity c : cheques) snapIds.add(c.getId());

	    IMAGE_EXEC.submit(() -> {
	        try (Session imgSession = HibernateUtil.getSession()) {
	            imgSession.doWork((Connection conn) -> {
	                String sql = "UPDATE cts_cheques SET front_image = ?, rear_image = ? WHERE id = ?";
	                try (PreparedStatement ps = conn.prepareStatement(sql)) {
	                    for (int i = 0; i < snapIds.size(); i++) {
	                        Long rowId = snapIds.get(i);
	                        if (rowId == null) continue;
	                        byte[] front = snapFronts.get(i);
	                        byte[] rear  = snapRears.get(i);
	                        if (front == null && rear == null) continue;
	                        if (front != null) ps.setBytes(1, front); else ps.setNull(1, java.sql.Types.BINARY);
	                        if (rear  != null) ps.setBytes(2, rear);  else ps.setNull(2, java.sql.Types.BINARY);
	                        ps.setLong(3, rowId);
	                        ps.addBatch();
	                    }
	                    ps.executeBatch();
	                }
	            });
	            LOG.info("Async image save complete for " + snapIds.size() + " cheques");
	        } catch (Exception ex) {
	            LOG.severe("Async image save failed: " + ex.getMessage());
	        }
	    });
	}

	@Override
	public Set<String> findExistingChequeNos(List<String> chequeNos) {
		if (chequeNos == null || chequeNos.isEmpty())
			return Collections.emptySet();
		try (Session session = HibernateUtil.getSession()) {
			@SuppressWarnings("unchecked")
			List<String> found = session
					.createNativeQuery("SELECT cheque_no FROM cts_cheques WHERE cheque_no IN :nos", String.class)
					.setParameter("nos", chequeNos)
					.getResultList();
			return new HashSet<>(found);
		} catch (Exception ex) {
			LOG.severe("findExistingChequeNos error: " + ex.getMessage());
			return Collections.emptySet();
		}
	}

	@Override
	public Set<String> loadReadyBatchIds(List<String> batchIds) {
		if (batchIds == null || batchIds.isEmpty())
			return Collections.emptySet();
		try (Session session = HibernateUtil.getSession()) {
			@SuppressWarnings("unchecked")
			List<String> ready = session.createNativeQuery(
					"SELECT batch_id FROM cts_cheques WHERE batch_id IN :ids "
					+ "GROUP BY batch_id "
					+ "HAVING COUNT(*) > 0 AND COUNT(*) = COUNT(CASE WHEN status = 'Ready' THEN 1 END)",
					String.class)
					.setParameter("ids", batchIds)
					.getResultList();
			return new HashSet<>(ready);
		} catch (Exception ex) {
			LOG.severe("loadReadyBatchIds error: " + ex.getMessage());
			return Collections.emptySet();
		}
	}

	@Override
	public List<ChequeEntity> loadChequesForBatch(String batchId) {
		try (Session session = HibernateUtil.getSession()) {
			List<Object[]> rows = session.createNativeQuery(
					"SELECT " + PROJECTION_COLS + " FROM cts_cheques WHERE batch_id = :batchId ORDER BY id",
					Object[].class)
					.setParameter("batchId", batchId)
					.getResultList();

			List<ChequeEntity> results = new ArrayList<>(rows.size());
			for (Object[] r : rows)
				results.add(mapProjectionRow(r));

			if (!results.isEmpty()) {
				List<Long> ids = results.stream().map(ChequeEntity::getId).toList();
				List<Object[]> extraRows = session.createNativeQuery(
						"SELECT id, transaction_code, amount_in_words, amount_words_mismatch, payee_account_no, base_no "
								+ "FROM cts_cheques WHERE id IN :ids",
						Object[].class)
						.setParameter("ids", ids).getResultList();
				Map<Long, Object[]> extraMap = new HashMap<>();
				for (Object[] row : extraRows)
					extraMap.put(toLong(row[0]), row);
				results.forEach(c -> {
					Object[] row = extraMap.get(c.getId());
					if (row != null) {
						c.setTransactionCode(row[1] != null ? row[1].toString() : null);
						c.setAmountInWords(row[2] != null ? row[2].toString() : null);
						c.setAmountWordsMismatch(row[3] != null && (Boolean) row[3]);
						c.setPayeeAccountNo(row[4] != null ? row[4].toString() : null);
						c.setBaseNo(row[5] != null ? row[5].toString() : null);
					}
				});
			}
			return results;
		} catch (Exception ex) {
			LOG.warning("loadChequesForBatch projection failed, falling back: " + ex.getMessage());
			return loadChequesForBatchFull(batchId);
		}
	}

	@Override
	public List<ChequeEntity> loadChequesForBatchFull(String batchId) {
		try (Session session = HibernateUtil.getSession()) {
			NativeQuery<ChequeEntity> q = session.createNativeQuery(
					"SELECT * FROM cts_cheques WHERE batch_id = :batchId ORDER BY id", ChequeEntity.class);
			q.setParameter("batchId", batchId);
			return q.list();
		} catch (Exception ex) {
			LOG.severe("loadChequesForBatchFull error: " + ex.getMessage());
			return Collections.emptyList();
		}
	}

	
	// ── 3. Load image bytes from Supabase via Hibernate ──────────────────
				//
				// loadChequeWithImages() uses session.get() which loads the full entity
				// including the BYTEA columns (front_image, rear_image).
				// The 17-field projection constructor intentionally excludes these.
				//
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
			session.createNativeMutationQuery("UPDATE cts_cheques SET status = :status, ver_status = :verStatus, "
					+ "updated_at = CURRENT_TIMESTAMP WHERE id = :id")
					.setParameter("status", status)
					.setParameter("verStatus", verStatus)
					.setParameter("id", chequeId).executeUpdate();
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
			session.createNativeMutationQuery("DELETE FROM cts_cheques WHERE id = :id")
					.setParameter("id", chequeId).executeUpdate();
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
			Number result = (Number) session
					.createNativeQuery("SELECT COUNT(*) FROM cts_cheques WHERE ver_status = 'Pending'", Object.class)
					.uniqueResult();
			return result != null ? result.longValue() : 0L;
		} catch (Exception ex) {
			LOG.severe("countPendingCheques error: " + ex.getMessage());
			return 0;
		}
	}

	@Override
	public List<ChequeEntity> loadIqaFailedCheques(String batchId) {
		try (Session session = HibernateUtil.getSession()) {
			List<Object[]> rows = session.createNativeQuery(
					"SELECT " + PROJECTION_COLS + " FROM cts_cheques WHERE batch_id = :batchId "
							+ "AND iqa_status = 'Fail' AND status = 'MICR_Repair' ORDER BY id",
					Object[].class)
					.setParameter("batchId", batchId).getResultList();

			List<ChequeEntity> results = new ArrayList<>(rows.size());
			for (Object[] r : rows)
				results.add(mapProjectionRow(r));
			return results;
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
			session.createNativeMutationQuery(
					"UPDATE cts_cheques SET "
					+ "  sort_code             = :sortCode, "
					+ "  transaction_code      = :txCode, "
					+ "  account_no            = :accountNo, "
					+ "  payee_account_no      = :payeeAccountNo, "
					+ "  base_no               = :baseNo, "
					+ "  amount                = :amount, "
					+ "  amount_in_words       = :amountInWords, "
					+ "  amount_words_mismatch = :mismatch, "
					+ "  cheque_date           = :chequeDate, "
					+ "  payee_name            = :payeeName, "
					+ "  status                = :status, "
					// FIX: ver_level removed — projection loads verLevel=null; writing it
					// here would clobber ver_level set by submitBatch()/verifier flow.
					+ "  updated_at            = CURRENT_TIMESTAMP "
					+ "WHERE id = :id")
					.setParameter("sortCode",       cheque.getSortCode())
					.setParameter("txCode",         cheque.getTransactionCode())
					.setParameter("accountNo",      cheque.getAccountNo())
					.setParameter("payeeAccountNo", cheque.getPayeeAccountNo())
					.setParameter("baseNo",         cheque.getBaseNo())
					.setParameter("amount",         cheque.getAmount())
					.setParameter("amountInWords",  cheque.getAmountInWords())
					.setParameter("mismatch",       cheque.isAmountWordsMismatch())
					.setParameter("chequeDate",     cheque.getChequeDate())
					.setParameter("payeeName",      cheque.getPayeeName())
					.setParameter("status",         cheque.getStatus())
					.setParameter("id",             cheque.getId())
					.executeUpdate();
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
			Number result = (Number) session
					.createNativeQuery(
							"SELECT COUNT(*) FROM cts_cheques "
									+ "WHERE status = 'Sent_for_Verification' AND updated_at >= CURRENT_DATE",
							Object.class)
					.uniqueResult();
			return result != null ? result.longValue() : 0L;
		} catch (Exception ex) {
			LOG.severe("countRepairedToday error: " + ex.getMessage());
			return 0L;
		}
	}

	@Override
	public List<ChequeEntity> loadHvChequesForBatch(String batchId) {
		try (Session session = HibernateUtil.getSession()) {
			List<Object[]> rows = session.createNativeQuery(
					"SELECT " + PROJECTION_COLS + " FROM cts_cheques WHERE batch_id = :batchId AND high_value = true ORDER BY id",
					Object[].class)
					.setParameter("batchId", batchId).getResultList();

			List<ChequeEntity> results = new ArrayList<>(rows.size());
			for (Object[] r : rows)
				results.add(mapProjectionRow(r));
			return results;
		} catch (Exception ex) {
			LOG.warning("loadHvChequesForBatch projection failed, fallback: " + ex.getMessage());
			try (Session session = HibernateUtil.getSession()) {
				NativeQuery<ChequeEntity> q = session.createNativeQuery(
						"SELECT * FROM cts_cheques WHERE batch_id = :batchId AND high_value = true ORDER BY id",
						ChequeEntity.class);
				q.setParameter("batchId", batchId);
				return q.list();
			} catch (Exception ex2) {
				LOG.severe("loadHvChequesForBatch fallback error: " + ex2.getMessage());
				return Collections.emptyList();
			}
		}
	}

	@Override
	public long countHvPendingCheques() {
		try (Session session = HibernateUtil.getSession()) {
			Number result = (Number) session.createNativeQuery(
					"SELECT COUNT(*) FROM cts_cheques WHERE high_value = true AND ver_status = 'Pending'", Object.class)
					.uniqueResult();
			return result != null ? result.longValue() : 0L;
		} catch (Exception ex) {
			LOG.severe("countHvPendingCheques error: " + ex.getMessage());
			return 0L;
		}
	}

	@Override
	public long countHvByVerStatus(String verStatus) {
		try (Session session = HibernateUtil.getSession()) {
			Number result = (Number) session
					.createNativeQuery("SELECT COUNT(*) FROM cts_cheques WHERE high_value = true AND ver_status = :vs", Object.class)
					.setParameter("vs", verStatus).uniqueResult();
			return result != null ? result.longValue() : 0L;
		} catch (Exception ex) {
			LOG.severe("countHvByVerStatus error: " + ex.getMessage());
			return 0L;
		}
	}

	@Override
	public long countHvPendingForBatch(String batchId) {
		try (Session session = HibernateUtil.getSession()) {
			Number result = (Number) session
					.createNativeQuery("SELECT COUNT(*) FROM cts_cheques WHERE batch_id = :batchId "
							+ "AND high_value = true AND ver_status = 'Pending'", Object.class)
					.setParameter("batchId", batchId).uniqueResult();
			return result != null ? result.longValue() : 0L;
		} catch (Exception ex) {
			LOG.severe("countHvPendingForBatch error: " + ex.getMessage());
			return 0L;
		}
	}

	@Override
	public BigDecimal sumHvPendingAmount() {
		try (Session session = HibernateUtil.getSession()) {
			Object result = session.createNativeQuery(
					"SELECT SUM(amount) FROM cts_cheques WHERE high_value = true AND ver_status = 'Pending'", Object.class)
					.uniqueResult();
			return result != null ? toBigDecimal(result) : BigDecimal.ZERO;
		} catch (Exception ex) {
			LOG.severe("sumHvPendingAmount error: " + ex.getMessage());
			return BigDecimal.ZERO;
		}
	}

	@Override
	public List<ChequeEntity> loadChequesByVerLevel(String verLevel, String status) {
		try (Session session = HibernateUtil.getSession()) {
			List<Object[]> rows = session.createNativeQuery(
					"SELECT " + PROJECTION_COLS + " FROM cts_cheques"
					+ " WHERE ver_level = :verLevel AND status = :status"
					+ " ORDER BY batch_id ASC, id ASC",
					Object[].class)
					.setParameter("verLevel", verLevel)
					.setParameter("status", status)
					.getResultList();

			List<ChequeEntity> results = new ArrayList<>(rows.size());
			for (Object[] r : rows)
				results.add(mapProjectionRow(r));

			if (!results.isEmpty()) {
				List<Long> ids = results.stream().map(ChequeEntity::getId).toList();
				List<Object[]> verRows = session.createNativeQuery(
						"SELECT id, ver_action, ver_by, ver_remarks FROM cts_cheques WHERE id IN :ids",
						Object[].class)
						.setParameter("ids", ids).getResultList();
				Map<Long, Object[]> verMap = new HashMap<>();
				for (Object[] row : verRows)
					verMap.put(toLong(row[0]), row);
				results.forEach(c -> {
					Object[] row = verMap.get(c.getId());
					if (row != null) {
						c.setVerAction(row[1] != null ? row[1].toString() : null);
						c.setVerBy(row[2] != null ? row[2].toString() : null);
						c.setVerRemarks(row[3] != null ? row[3].toString() : null);
					}
				});
			}
			return results;
		} catch (Exception ex) {
			LOG.severe("loadChequesByVerLevel error: " + ex.getMessage());
			return Collections.emptyList();
		}
	}

	@Override
	public long countPendingVerificationForBatch(String batchId) {
		try (Session session = HibernateUtil.getSession()) {
			Number result = (Number) session.createNativeQuery(
					"SELECT COUNT(*) FROM cts_cheques"
					+ " WHERE batch_id = :batchId"
					+ " AND status IN ('V1_PENDING', 'V2_PENDING')",
					Object.class)
					.setParameter("batchId", batchId)
					.uniqueResult();
			return result != null ? result.longValue() : 0L;
		} catch (Exception ex) {
			LOG.severe("countPendingVerificationForBatch error: " + ex.getMessage());
			return -1L;
		}
	}

	@Override
	public void applyVerifierAction(Long chequeId, String status, String verLevel,
			String verAction, String verBy, String verRemarks) {
		Transaction tx = null;
		try (Session session = HibernateUtil.getSession()) {
			tx = session.beginTransaction();
			session.createNativeMutationQuery(
					"UPDATE cts_cheques SET"
					+ "  status      = :status,"
					+ "  ver_status  = :status,"
					+ "  ver_level   = :verLevel,"
					+ "  ver_action  = :verAction,"
					+ "  ver_by      = :verBy,"
					+ "  ver_remarks = :verRemarks,"
					+ "  updated_at  = CURRENT_TIMESTAMP"
					+ " WHERE id = :id")
					.setParameter("status",     status)
					.setParameter("verLevel",   verLevel)
					.setParameter("verAction",  verAction)
					.setParameter("verBy",      verBy)
					.setParameter("verRemarks", verRemarks)
					.setParameter("id",         chequeId)
					.executeUpdate();
			tx.commit();
			LOG.info("applyVerifierAction: cheque=" + chequeId + " action=" + verAction + " by=" + verBy);
		} catch (Exception ex) {
			if (tx != null)
				tx.rollback();
			LOG.severe("applyVerifierAction error: " + ex.getMessage());
			throw new RuntimeException("Failed to apply verifier action: " + ex.getMessage(), ex);
		}
	}

	@Override
	public List<ChequeEntity> loadAcceptedInstrumentsForCxf() {
		try (Session session = HibernateUtil.getSession()) {
			List<Object[]> rows = session.createNativeQuery(
					"SELECT " + PROJECTION_COLS + " FROM cts_cheques WHERE status = 'VERIFIED' "
							+ "  AND (ver_status IS NULL OR ver_status <> 'CXF_Generated') "
							+ "ORDER BY batch_id ASC, id ASC",
					Object[].class)
					.getResultList();

			List<ChequeEntity> results = new ArrayList<>(rows.size());
			for (Object[] r : rows)
				results.add(mapProjectionRow(r));

			if (results.isEmpty())
				return results;

			List<Long> ids = results.stream().map(ChequeEntity::getId).toList();
			List<Object[]> tcRows = session
					.createNativeQuery("SELECT id, transaction_code FROM cts_cheques WHERE id IN :ids", Object[].class)
					.setParameter("ids", ids).getResultList();

			Map<Long, String> tcMap = new HashMap<>();
			for (Object[] row : tcRows)
				tcMap.put(toLong(row[0]), (String) row[1]);
			results.forEach(c -> c.setTransactionCode(tcMap.get(c.getId())));

			LOG.info("loadAcceptedInstrumentsForCxf: " + results.size() + " instruments ready.");
			return results;
		} catch (Exception ex) {
			LOG.severe("loadAcceptedInstrumentsForCxf error: " + ex.getMessage());
			return Collections.emptyList();
		}
	}

	@Override
	public void referToVerificationTwo(Long chequeId, String verBy, String verRemarks) {
		Transaction tx = null;
		try (Session session = HibernateUtil.getSession()) {
			tx = session.beginTransaction();
			session.createNativeMutationQuery(
					"UPDATE cts_cheques SET"
					+ "  status      = :status,"
					+ "  ver_status  = :status,"
					+ "  ver_level   = :verLevel,"
					+ "  is_referred = true,"
					+ "  ver_action  = :verAction,"
					+ "  ver_by      = :verBy,"
					+ "  ver_remarks = :verRemarks,"
					+ "  updated_at  = CURRENT_TIMESTAMP"
					+ " WHERE id = :id")
					.setParameter("status",     "V2_PENDING")
					.setParameter("verLevel",   "V2")
					.setParameter("verAction",  "Refer")
					.setParameter("verBy",      verBy)
					.setParameter("verRemarks", verRemarks)
					.setParameter("id",         chequeId)
					.executeUpdate();
			tx.commit();
			LOG.info("referToVerificationTwo: cheque=" + chequeId + " by=" + verBy);
		} catch (Exception ex) {
			if (tx != null)
				tx.rollback();
			LOG.severe("referToVerificationTwo error: " + ex.getMessage());
			throw new RuntimeException("Failed to refer cheque to V2: " + ex.getMessage(), ex);
		}
	}

	@Override
	public void updateVerRouting(Long chequeId, String status, String verLevel, String verStatus) {
		Transaction tx = null;
		try (Session session = HibernateUtil.getSession()) {
			tx = session.beginTransaction();
			session.createNativeMutationQuery(
					"UPDATE cts_cheques SET"
					+ "  status     = :status,"
					+ "  ver_level  = :verLevel,"
					+ "  ver_status = :verStatus,"
					+ "  updated_at = CURRENT_TIMESTAMP"
					+ " WHERE id = :id")
					.setParameter("status",    status)
					.setParameter("verLevel",  verLevel)
					.setParameter("verStatus", verStatus)
					.setParameter("id",        chequeId)
					.executeUpdate();
			tx.commit();
			LOG.info("updateVerRouting: cheque=" + chequeId + " status=" + status + " verLevel=" + verLevel);
		} catch (Exception ex) {
			if (tx != null) tx.rollback();
			LOG.severe("updateVerRouting error: " + ex.getMessage());
			throw new RuntimeException("updateVerRouting failed: " + ex.getMessage(), ex);
		}
	}
}
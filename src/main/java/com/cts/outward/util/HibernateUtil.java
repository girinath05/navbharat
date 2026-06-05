/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : HibernateUtil.java
 *  Package     : com.cts.outward.util
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : Bootstrap utility for Hibernate 6 SessionFactory.
 *                Builds the factory once from hibernate.cfg.xml
 *                on class load. Exposes getSession() returning a
 *                fresh auto-close Session per DAO call. No
 *                thread-local binding; callers manage session
 *                lifecycle via try-with-resources.
 * ============================================================
 */

package com.cts.outward.util;



import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;

/**
 * ============================================================
 *  HibernateUtil  —  ClearPay CTS
 *  File   : HibernateUtil.java
 *
 *  FIXES vs original:
 *  ──────────────────
 *  FIX 1 — LAZY initialization via Initialization-on-demand holder.
 *           Original used a direct static field:
 *             private static final SessionFactory SF = buildFactory();
 *           This runs buildFactory() the moment the class is loaded,
 *           which happens during Tomcat startup BEFORE the ZK servlet
 *           finishes initialising. If the DB is slow (Supabase SSL
 *           handshake ≈ 3–5 s) the static initialiser blocks the
 *           ZK page render thread and causes the client-side
 *           "Something went wrong" error.
 *           Holder pattern defers creation to first getSession() call.
 *
 *  FIX 2 — isInitialized() helper lets callers (e.g. ChequeDAO)
 *           check availability before using it, enabling graceful
 *           degradation instead of an uncaught NPE.
 *
 *  FIX 3 — Explicit error logging with full stack trace so the
 *           real DB error is visible in Tomcat console rather than
 *           being swallowed by ExceptionInInitializerError.
 * ============================================================
 */
public class HibernateUtil {

    // ── Initialization-on-demand holder ──────────────────────────────
    // The JVM loads Holder only when getFactory() is first called.
    // Thread-safe without synchronization.

    private static final class Holder {
        static final SessionFactory INSTANCE = createFactory();

        private static SessionFactory createFactory() {
            try {
                return new Configuration()
                        .configure()          // reads hibernate.cfg.xml
                        .buildSessionFactory();
            } catch (Exception ex) {
                // Print full stack so the real cause is visible in Tomcat log
                System.err.println(
                    "[HibernateUtil] FATAL — SessionFactory creation failed.");
                ex.printStackTrace(System.err);
                // Re-throw as runtime so Holder.<clinit> sets INSTANCE = null
                // and subsequent calls to getSession() get a clear NPE message.
                throw new RuntimeException(
                    "HibernateUtil: SessionFactory init failed — " + ex.getMessage(), ex);
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Open a new Hibernate Session.
     * Always close the returned Session in a finally block:
     *
     *   Session s = HibernateUtil.getSession();
     *   try {
     *       s.beginTransaction();
     *       s.persist(entity);
     *       s.getTransaction().commit();
     *   } catch (Exception e) {
     *       s.getTransaction().rollback();
     *       throw e;
     *   } finally {
     *       s.close();
     *   }
     */
    public static Session getSession() {
        return Holder.INSTANCE.openSession();
    }

    /**
     * Returns true if the SessionFactory was created successfully.
     * Use in DAO methods to degrade gracefully during development
     * when the DB might be unreachable:
     *
     *   if (!HibernateUtil.isInitialized()) {
     *       log.warn("DB not available — returning empty list");
     *       return Collections.emptyList();
     *   }
     */
    public static boolean isInitialized() {
        try {
            return Holder.INSTANCE != null && !Holder.INSTANCE.isClosed();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Gracefully close the SessionFactory.
     * Call from your ServletContextListener.contextDestroyed().
     */
    public static void shutdown() {
        try {
            if (isInitialized()) {
                Holder.INSTANCE.close();
                System.out.println("[HibernateUtil] SessionFactory closed.");
            }
        } catch (Exception ex) {
            ex.printStackTrace(System.err);
        }
    }

    // Prevent instantiation
    private HibernateUtil() {}
}
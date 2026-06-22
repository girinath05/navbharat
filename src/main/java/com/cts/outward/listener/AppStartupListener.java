/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : AppStartupListener.java
 *  Package     : com.cts.outward.listener
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : ServletContextListener that fires on Tomcat
 *                startup (contextInitialized) to pre-warm:
 *
 *                1. HibernateUtil.getSessionFactory()
 *                   Builds SessionFactory eagerly — scans entity
 *                   classes, validates schema, opens JDBC
 *                   connection pool to Supabase. This is the
 *                   2-5s cold-start cost. Paying it at deploy
 *                   time means the first user request hits a
 *                   warm pool, not a cold one.
 *
 *                2. A single lightweight test query
 *                   "SELECT 1" opens and exercises the first
 *                   real Supabase TCP+TLS connection so the
 *                   pool has at least one warm slot ready.
 *
 *                Registration: declared in web.xml (see below).
 *                Must come BEFORE zkLoader servlet in web.xml
 *                so the pool is ready before any ZUL is served.
 *
 *  web.xml snippet:
 *  ───────────────
 *    <listener>
 *      <listener-class>
 *        com.cts.outward.listener.AppStartupListener
 *      </listener-class>
 *    </listener>
 * ============================================================
 */

package com.cts.outward.listener;

import java.util.logging.Logger;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;

import com.cts.outward.util.HibernateUtil;

public class AppStartupListener implements ServletContextListener {

    private static final Logger LOG = Logger.getLogger(AppStartupListener.class.getName());

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        LOG.info("AppStartupListener: warming Hibernate SessionFactory + Supabase connection...");
        long t0 = System.currentTimeMillis();
        try {
            // Step 1 + 2 combined: calling getSession() triggers the Holder class-load
            // which calls createFactory() — builds SessionFactory AND opens first connection.

            // Step 2: fire one lightweight query to open and exercise the first
            //         real TCP+TLS connection to Supabase before any user arrives.
            try (org.hibernate.Session session = HibernateUtil.getSession()) {
                Number result = (Number) session
                        .createNativeQuery("SELECT 1", Number.class)
                        .uniqueResult();
                LOG.info("AppStartupListener: warmup query returned " + result
                        + " — pool ready in " + (System.currentTimeMillis() - t0) + "ms");
            }

        } catch (Exception ex) {
            // Log but do NOT rethrow — a warmup failure must not block Tomcat startup.
            // The app can still function; the first user request will pay the cold-start cost.
            LOG.warning("AppStartupListener: warmup failed (non-fatal) — " + ex.getMessage());
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        LOG.info("AppStartupListener: closing Hibernate SessionFactory...");
        try {
            HibernateUtil.shutdown();
        } catch (Exception ex) {
            LOG.warning("AppStartupListener: shutdown error — " + ex.getMessage());
        }
    }
}
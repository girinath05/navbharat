package com.cts;

import com.cts.scheduler.UserUnlockScheduler;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AppContextListener
 * ==================
 * Servlet context lifecycle listener registered in web.xml.
 * Starts {@link UserUnlockScheduler} when the web application initialises
 * and stops it cleanly when the application is shut down.
 *
 * Without this listener the scheduler thread is never created,
 * so timed user-unlock events never fire.
 */
public class AppContextListener implements ServletContextListener {

    private static final Logger LOG = LoggerFactory.getLogger(AppContextListener.class);

    private final UserUnlockScheduler scheduler = new UserUnlockScheduler();

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        LOG.info("AppContextListener: application starting — launching UserUnlockScheduler");
        scheduler.start();
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        LOG.info("AppContextListener: application stopping — shutting down UserUnlockScheduler");
        scheduler.stop();
    }
}

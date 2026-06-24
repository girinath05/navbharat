package com.cts.scheduler;

import com.cts.uam.dao.UserDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background scheduler that runs every 60 seconds.
 * Finds all LOCKED users whose lockedUntil has passed and sets them ACTIVE.
 * Does NOT touch indefinite locks (lockedUntil = 9999-12-31).
 */
public class UserUnlockScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(UserUnlockScheduler.class);
    private static final int INTERVAL_SECONDS = 60;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "user-unlock-scheduler");
        t.setDaemon(true); // won't block JVM shutdown
        return t;
    });

    private final UserDAO userDAO = new UserDAO();

    public void start() {
        scheduler.scheduleAtFixedRate(this::unlockExpired, INTERVAL_SECONDS, INTERVAL_SECONDS, TimeUnit.SECONDS);
        LOG.info("UserUnlockScheduler started — checking every {} seconds.", INTERVAL_SECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
        LOG.info("UserUnlockScheduler stopped.");
    }

    private void unlockExpired() {
        try {
            int count = userDAO.autoUnlockExpiredLocks();
            if (count > 0) {
                LOG.info("UserUnlockScheduler: auto-unlocked {} user(s) whose lock duration expired.", count);
            }
        } catch (Exception ex) {
            LOG.error("UserUnlockScheduler error during auto-unlock: {}", ex.getMessage(), ex);
        }
    }
}

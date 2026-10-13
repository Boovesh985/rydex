package com.rydex.tracking.support;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Runs side effects (Redis writes, lock releases) only once the database change is durable. */
public final class AfterCommit {

    private AfterCommit() {
    }

    /** Runs {@code action} after the current transaction commits, or immediately when there is none. */
    public static void run(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}

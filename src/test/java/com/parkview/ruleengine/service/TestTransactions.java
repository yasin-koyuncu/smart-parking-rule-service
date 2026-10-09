package com.parkview.ruleengine.service;

import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** A transaction template over a no-op transaction manager, for unit tests that only need the callback to run. */
final class TestTransactions {

    private TestTransactions() {
    }

    static TransactionTemplate template() {
        return new TransactionTemplate(new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
            }
        });
    }
}

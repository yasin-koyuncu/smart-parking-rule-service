package com.parkview.ruleengine.service.retention;

/** Lets one instance at a time run the retention job. */
public interface RetentionLock {

    /**
     * Runs {@code action} when this instance gets the lock, otherwise does nothing.
     *
     * @return {@code true} when the action ran, {@code false} when another instance holds the lock
     */
    boolean runIfFree(Runnable action);
}

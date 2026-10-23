package com.parkview.ruleengine.service.outbox;

import org.springframework.amqp.rabbit.connection.CorrelationData;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Reads the outcome of a publisher confirm: acknowledged and routed, acknowledged but unroutable, or failed. */
final class PublisherConfirms {

    enum Status {
        /** The broker acknowledged the message and routed it to at least one queue. */
        ROUTED,
        /** The broker acknowledged the message but no queue is bound for its routing key (it was returned). */
        UNROUTABLE,
        /** No acknowledgement: nack, timeout, or an error while waiting. */
        FAILED
    }

    record Outcome(Status status, String detail) {
    }

    private static final Outcome ROUTED = new Outcome(Status.ROUTED, null);

    private PublisherConfirms() {
    }

    static Outcome await(CorrelationData correlation, long deadlineNanos) {
        long remaining = Math.max(0, deadlineNanos - System.nanoTime());
        try {
            CorrelationData.Confirm confirm = correlation.getFuture().get(remaining, TimeUnit.NANOSECONDS);
            if (!confirm.isAck()) {
                return failed("nacked by broker: " + confirm.getReason());
            }
            if (correlation.getReturned() != null) {
                return new Outcome(Status.UNROUTABLE, "unroutable: " + correlation.getReturned().getReplyText());
            }
            return ROUTED;
        } catch (TimeoutException e) {
            return failed("no publisher confirm in time");
        } catch (ExecutionException e) {
            return failed("confirm failed: " + e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed("interrupted while waiting for the publisher confirm");
        }
    }

    private static Outcome failed(String detail) {
        return new Outcome(Status.FAILED, detail);
    }
}

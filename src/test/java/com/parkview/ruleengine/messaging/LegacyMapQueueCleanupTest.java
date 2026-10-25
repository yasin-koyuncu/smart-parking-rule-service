package com.parkview.ruleengine.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.core.AmqpAdmin;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The durable queue that all instances shared before {@code map.updated} got a queue per instance is removed from the
 * broker instead of being left to collect events. Not covered without a broker: the real deletion.
 */
class LegacyMapQueueCleanupTest {

    @Test
    void theOldSharedQueueIsDeletedWhenNobodyConsumesIt() {
        AmqpAdmin admin = mock(AmqpAdmin.class);

        new LegacyMapQueueCleanup(admin).removeLegacyQueue();

        verify(admin).deleteQueue("map.updated.rule-engine", true, false);   // if-unused, whatever it still holds
    }

    @Test
    void anOldInstanceStillConsumingItDoesNotStopTheStart() {
        AmqpAdmin admin = mock(AmqpAdmin.class);
        doThrow(new AmqpIOException(new IOException("PRECONDITION_FAILED - queue in use")))
                .when(admin).deleteQueue("map.updated.rule-engine", true, false);

        assertThatCode(() -> new LegacyMapQueueCleanup(admin).removeLegacyQueue()).doesNotThrowAnyException();
    }
}

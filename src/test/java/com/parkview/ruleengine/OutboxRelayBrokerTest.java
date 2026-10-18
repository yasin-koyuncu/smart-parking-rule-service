package com.parkview.ruleengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkview.ruleengine.config.OutboxProperties;
import com.parkview.ruleengine.domain.OutboxEvent;
import com.parkview.ruleengine.repository.OutboxEventRepository;
import com.parkview.ruleengine.service.OutboxRelay;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The relay against a real broker with the application's own RabbitTemplate configuration (publisher confirms, mandatory
 * flag): a routed message is confirmed and delivered with the outbox row id as messageId and the headers of the former direct
 * publish; an unroutable one is returned by the broker and, by default, counted and treated as delivered, or with
 * {@code retry-unroutable} kept until a consumer queue exists. The scheduled relay of the application is switched off here so
 * that the test drives the relay itself.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.datasource.password=unused-overridden-by-service-connection",
        "parkview.security.issuer=http://localhost:1/auth/v1",
        "parkview.outbox.relay-enabled=false"})
class OutboxRelayBrokerTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withInitScript("test-schema.sql");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");

    @Autowired
    OutboxEventRepository outbox;
    @Autowired
    RabbitAdmin admin;
    @Autowired
    RabbitTemplate template;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    TransactionTemplate tx;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private OutboxRelay relay(boolean retryUnroutable) {
        OutboxProperties properties = new OutboxProperties(true, Duration.ofMillis(250), 50, Duration.ofSeconds(5), Duration.ofSeconds(60),
                Duration.ofDays(14), retryUnroutable);
        return new OutboxRelay(outbox, template, properties, Clock.systemUTC(), meters);
    }

    private Queue bind(String queueName, String routingKey) {
        Queue queue = new Queue(queueName, false, false, false);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(new TopicExchange("parkview.violation", true, false)).with(routingKey));
        return queue;
    }

    /** The scheduled relay runs in a transaction; so does the one the test drives. */
    private int run(OutboxRelay relay) {
        return tx.execute(status -> relay.relayDueEvents());
    }

    private OutboxEvent enqueue(String aggregate, String routingKey, String payload) {
        return outbox.saveAndFlush(OutboxEvent.create("fine", aggregate, "parkview.violation", routingKey, payload, "req-" + aggregate, Instant.now()));
    }

    @Test
    void aRoutedMessageIsDeliveredWithTheRowIdAsMessageIdTheExactPayloadAndTheHeaders() throws Exception {
        String key = "fine.voided." + UUID.randomUUID();
        Queue queue = bind("relay-test-" + UUID.randomUUID(), key);
        String aggregate = UUID.randomUUID().toString();
        OutboxEvent routed = enqueue(aggregate, key, "{\"zzz\":1,\"fineId\":\"" + aggregate + "\",\"amountSek\":700.00}");

        assertThat(run(relay(false))).isGreaterThanOrEqualTo(1);

        Message delivered = template.receive(queue.getName(), 5000);
        assertThat(delivered).isNotNull();
        var props = delivered.getMessageProperties();
        assertThat(props.getMessageId()).isEqualTo(routed.getId().toString());
        assertThat(props.getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(props.getContentType()).isEqualTo("application/json");
        assertThat(props.getReceivedRoutingKey()).isEqualTo(key);
        assertThat((String) props.getHeader("x-event-type")).isEqualTo(key);
        assertThat((String) props.getHeader("x-source")).isEqualTo("rule-service");
        assertThat((String) props.getHeader("x-request-id")).isEqualTo("req-" + aggregate);
        assertThat(new String(delivered.getBody())).isEqualTo("{\"zzz\":1,\"fineId\":\"" + aggregate + "\",\"amountSek\":700.00}");
        JsonNode body = objectMapper.readTree(delivered.getBody());
        assertThat(body.get("amountSek").decimalValue()).isEqualByComparingTo("700");
        assertThat(jdbc.queryForObject("select published_at is not null from rule_outbox_events where id = ?", Boolean.class, routed.getId())).isTrue();
    }

    @Test
    void anUnroutableMessageIsCountedAndTreatedAsDeliveredByDefault() {
        OutboxEvent unroutable = enqueue(UUID.randomUUID().toString(), "fine.nobody.listens." + UUID.randomUUID(), "{\"a\":1}");
        OutboxRelay relay = relay(false);

        run(relay);

        assertThat(jdbc.queryForObject("select published_at is not null from rule_outbox_events where id = ?", Boolean.class, unroutable.getId())).isTrue();
        assertThat(meters.get("rule.outbox.unroutable").counter().count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void withRetryUnroutableTheRowStaysUntilAConsumerQueueExists() {
        String key = "fine.late.consumer." + UUID.randomUUID();
        OutboxEvent unroutable = enqueue(UUID.randomUUID().toString(), key, "{\"a\":1}");

        run(relay(true));

        assertThat(jdbc.queryForObject("select published_at is null from rule_outbox_events where id = ?", Boolean.class, unroutable.getId())).isTrue();
        assertThat(jdbc.queryForObject("select attempts from rule_outbox_events where id = ?", Integer.class, unroutable.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select last_error from rule_outbox_events where id = ?", String.class, unroutable.getId())).contains("unroutable");

        Queue queue = bind("relay-test-late-" + UUID.randomUUID(), key);
        jdbc.update("update rule_outbox_events set next_attempt_at = now() where id = ?", unroutable.getId());
        run(relay(true));

        Message retried = template.receive(queue.getName(), 5000);
        assertThat(retried).isNotNull();
        assertThat(retried.getMessageProperties().getMessageId()).isEqualTo(unroutable.getId().toString());
    }

    @Test
    void theEventsOfOneFineArePublishedOneAfterTheOtherInTheOrderTheyWereWritten() {
        String key = "fine.ordered." + UUID.randomUUID();
        Queue queue = bind("relay-test-order-" + UUID.randomUUID(), key);
        String aggregate = UUID.randomUUID().toString();
        enqueue(aggregate, key, "{\"n\":1}");
        enqueue(aggregate, key, "{\"n\":2}");
        enqueue(aggregate, key, "{\"n\":3}");
        OutboxRelay relay = relay(false);

        for (int run = 0; run < 3; run++) {
            run(relay);
        }

        for (int n = 1; n <= 3; n++) {
            Message message = template.receive(queue.getName(), 5000);
            assertThat(message).isNotNull();
            assertThat(new String(message.getBody())).isEqualTo("{\"n\":" + n + "}");
        }
    }
}

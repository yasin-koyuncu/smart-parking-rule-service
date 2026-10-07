package com.parkview.ruleengine.messaging;

import com.parkview.ruleengine.repository.FineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Argument;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Lyssnar på payment.completed.fine från payment-service och markerar
 * motsvarande Fine som betald. Egen kö (payment.completed.rule-engine)
 * bunden till den bot-specifika routing-nyckeln — payment-service
 * publicerar payment.completed.fine / payment.completed.session separat,
 * så bindningen filtrerar redan på referenstyp och behöver ingen egen
 * kontroll i koden.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventListener {

    private final FineRepository fineRepository;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(
                    value = "payment.completed.rule-engine",
                    durable = "true",
                    arguments = @Argument(name = "x-dead-letter-exchange", value = "parkview.dlx")
            ),
            exchange = @Exchange(value = "parkview.payment", type = ExchangeTypes.TOPIC),
            key = "payment.completed.fine"
    ))
    @Transactional
    public void onPaymentCompleted(Map<String, Object> event) {
        Object referenceId = event.get("referenceId");
        if (referenceId == null) {
            log.warn("payment.completed saknar referenceId: {}", event);
            return;
        }

        try {
            UUID fineId = UUID.fromString(referenceId.toString());
            fineRepository.findById(fineId).ifPresentOrElse(
                    fine -> {
                        fine.setPaid(true);
                        fineRepository.save(fine);
                        log.info("Bot markerad betald: {}", fineId);
                    },
                    () -> log.warn("Fine {} hittades inte för payment.completed", fineId)
            );
        } catch (IllegalArgumentException e) {
            log.warn("Ogiltigt referenceId i payment.completed: {}", referenceId);
        }
    }
}

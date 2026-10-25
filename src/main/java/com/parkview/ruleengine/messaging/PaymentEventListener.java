package com.parkview.ruleengine.messaging;

import com.parkview.ruleengine.config.RabbitConfig;
import com.parkview.ruleengine.dto.event.PaymentCompletedEvent;
import com.parkview.ruleengine.service.fine.FineSettlementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code payment.completed.fine} from payment-service and marks the fine as paid. The
 * queue is bound to the fine-specific routing key, so session payments never arrive here. The first payment
 * also publishes {@code fine.paid}; repeats change nothing.
 */
@Component
@RequiredArgsConstructor
public class PaymentEventListener {

    private final FineSettlementService fineSettlement;

    @RabbitListener(queues = RabbitConfig.PAYMENT_COMPLETED_QUEUE)
    public void onPaymentCompleted(@Payload @Valid PaymentCompletedEvent event) {
        fineSettlement.markPaidOnline(event.referenceId(), event.paymentId(), event.amountSek());
    }
}

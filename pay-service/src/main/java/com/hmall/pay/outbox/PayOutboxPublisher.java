package com.hmall.pay.outbox;

import com.hmall.api.client.TradeClient;
import com.hmall.api.client.VoucherOrderClient;
import com.hmall.api.dto.OrderSummaryDTO;
import com.hmall.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class PayOutboxPublisher {
    private final PayOutboxRepository repository;
    private final RabbitTemplate rabbit;
    private final TradeClient tradeClient;
    private final VoucherOrderClient voucherClient;
    private long reconciliationCursor;

    @PostConstruct
    public void requireRoutableMessages() {
        rabbit.setMandatory(true);
    }

    @Scheduled(initialDelay = 10000, fixedDelay = 60000)
    public void reconcile() {
        try {
            int repaired = repository.reconcileMissing();
            if (repaired > 0) log.warn("Recreated {} missing payment outbox events", repaired);
            java.util.List<PayOutboxRepository.Reconciliation> batch = repository.paidAfter(reconciliationCursor);
            if (batch.isEmpty() && reconciliationCursor != 0) {
                reconciliationCursor = 0;
                batch = repository.paidAfter(0);
            }
            for (PayOutboxRepository.Reconciliation row : batch) {
                reconciliationCursor = row.id;
                try {
                    UserContext.setUser(row.userId);
                    OrderSummaryDTO business = "{\"source\":\"voucher\"}".equals(row.source)
                            ? voucherClient.getPaymentSummary(row.bizOrderNo)
                            : tradeClient.getOrder(row.bizOrderNo);
                    if (business != null && Integer.valueOf(1).equals(business.getStatus())) {
                        repository.requeue(row.id);
                        log.warn("Payment {} is paid but business order {} is pending; notification requeued",
                                row.id, row.bizOrderNo);
                    } else if (business == null) {
                        log.error("Payment {} has no business order {}", row.id, row.bizOrderNo);
                    }
                } catch (Exception e) {
                    log.error("Payment {} business reconciliation failed", row.id, e);
                } finally {
                    UserContext.removeUser();
                }
            }
        } catch (Exception e) {
            log.error("Payment outbox reconciliation failed", e);
        }
    }

    @Scheduled(initialDelay = 1500, fixedDelayString = "${pay.outbox.poll-ms:1000}")
    public void publishDue() {
        for (PayOutboxRepository.Event event : repository.due()) {
            if (!repository.lease(event.id)) continue;
            try {
                CorrelationData correlation = new CorrelationData(event.id.toString());
                rabbit.convertAndSend("pay.direct", event.routingKey, event.bizOrderNo, correlation);
                CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
                if (!confirm.isAck()) throw new IllegalStateException("broker nack: " + confirm.getReason());
                if (correlation.getReturned() != null) {
                    throw new IllegalStateException("unroutable: " + correlation.getReturned().getReplyText());
                }
                repository.sent(event.id);
            } catch (Exception e) {
                int delay = Math.min(60, 1 << Math.min(event.attempts + 1, 6));
                String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                repository.retry(event.id, delay, error.substring(0, Math.min(255, error.length())));
                log.warn("Payment outbox event {} will retry in {} seconds: {}", event.id, delay, error);
            }
        }
    }
}

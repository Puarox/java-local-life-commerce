package com.hmdp.listener;

import com.hmdp.service.impl.VoucherOrderServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VoucherPayStatusListener {
    private final VoucherOrderServiceImpl voucherOrderService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "voucher.pay.success.queue", durable = "true"),
            exchange = @Exchange(name = "pay.direct"),
            key = "voucher.pay.success"
    ))
    public void onPaid(Long voucherOrderId) {
        voucherOrderService.markPaid(voucherOrderId);
    }
}

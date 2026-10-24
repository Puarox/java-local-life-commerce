package com.hmdp.listener;

import com.hmdp.service.impl.VoucherOrderServiceImpl;
import com.hmdp.config.VoucherPayQueueTopology;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VoucherPayStatusListener {
    private final VoucherOrderServiceImpl voucherOrderService;

    @RabbitListener(queues = VoucherPayQueueTopology.PAY_QUEUE)
    public void onPaid(Long voucherOrderId) {
        voucherOrderService.markPaid(voucherOrderId);
    }
}

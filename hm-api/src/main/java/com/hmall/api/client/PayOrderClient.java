package com.hmall.api.client;

import com.hmall.api.config.DefaultFeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;

@FeignClient(value = "service-pay", configuration = DefaultFeignConfig.class)
public interface PayOrderClient {
    @PutMapping("/internal/pay-orders/voucher/{voucherOrderId}/close")
    void closeUnpaidVoucher(@PathVariable("voucherOrderId") Long voucherOrderId);
}

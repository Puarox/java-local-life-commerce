package com.hmall.api.client;

import com.hmall.api.config.DefaultFeignConfig;
import com.hmall.api.dto.OrderSummaryDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(value = "local-life-service", configuration = DefaultFeignConfig.class)
public interface VoucherOrderClient {
    @GetMapping("/voucher-order/payment/{id}")
    OrderSummaryDTO getPaymentSummary(@PathVariable("id") Long id);
}

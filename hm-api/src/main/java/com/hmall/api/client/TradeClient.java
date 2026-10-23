package com.hmall.api.client;

import com.hmall.api.config.DefaultFeignConfig;
import com.hmall.api.dto.OrderSummaryDTO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(value="service-trade",configuration = DefaultFeignConfig.class)
public interface TradeClient {
    @GetMapping("/orders/{orderId}")
    OrderSummaryDTO getOrder(@PathVariable("orderId") Long orderId);

}

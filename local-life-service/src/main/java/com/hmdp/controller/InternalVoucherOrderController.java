package com.hmdp.controller;

import com.hmdp.service.impl.VoucherOrderServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/voucher-orders")
@RequiredArgsConstructor
public class InternalVoucherOrderController {
    private final VoucherOrderServiceImpl orders;

    @PutMapping("/{id}/refund")
    public void markRefunded(@PathVariable Long id) {
        orders.markRefunded(id);
    }
}

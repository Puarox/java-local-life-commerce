package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmall.api.dto.OrderSummaryDTO;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {
    @Autowired
    private VoucherOrderServiceImpl voucherOrderService;

    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {

        return voucherOrderService.sckillVoucher(voucherId);
    }

    @PostMapping("buy/{id}")
    public Result buyRegularVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.buyRegularVoucher(voucherId);
    }

    @GetMapping("/me")
    public Result myOrders(@RequestParam(value = "current", defaultValue = "1") int current) {
        return voucherOrderService.myOrders(current);
    }

    @GetMapping("/payment/{id}")
    public OrderSummaryDTO paymentSummary(@PathVariable("id") Long id) {
        return voucherOrderService.getPaymentSummary(id);
    }
}

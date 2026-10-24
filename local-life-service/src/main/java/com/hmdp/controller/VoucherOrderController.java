package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmall.api.dto.OrderSummaryDTO;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import com.hmall.common.exception.ForbiddenException;
import com.hmall.common.utils.UserContext;
import org.springframework.beans.factory.annotation.Value;
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
    @Value("${LOCAL_LIFE_ADMIN_USER_ID:0}")
    private Long adminUserId;

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

    @PostMapping("/{id}/cancel")
    public Result cancel(@PathVariable("id") Long id) {
        voucherOrderService.cancelUnpaid(id);
        return Result.ok();
    }

    @GetMapping("/merchant/permission")
    public Result merchantPermission() {
        requireAdmin();
        return Result.ok(true);
    }

    @PostMapping("/redeem")
    public Result redeem(@RequestParam("code") String code) {
        requireAdmin();
        return Result.ok(voucherOrderService.redeem(code).toString());
    }

    private void requireAdmin() {
        if (!adminUserId.equals(UserContext.getUser())) throw new ForbiddenException("仅演示商户管理员可核销");
    }
}

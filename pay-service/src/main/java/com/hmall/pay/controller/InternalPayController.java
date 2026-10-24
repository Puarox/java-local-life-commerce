package com.hmall.pay.controller;

import com.hmall.common.exception.BizIllegalException;
import com.hmall.common.exception.ForbiddenException;
import com.hmall.common.utils.UserContext;
import com.hmall.pay.domain.po.PayOrder;
import com.hmall.pay.enums.PayStatus;
import com.hmall.pay.service.IPayOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/pay-orders")
@RequiredArgsConstructor
public class InternalPayController {
    private final IPayOrderService orders;

    @PutMapping("/voucher/{voucherOrderId}/close")
    @Transactional
    public void closeUnpaidVoucher(@PathVariable Long voucherOrderId) {
        PayOrder order = orders.lambdaQuery().eq(PayOrder::getBizOrderNo, voucherOrderId).one();
        if (order == null) return;
        if (!order.getBizUserId().equals(UserContext.getUser())
                || !"{\"source\":\"voucher\"}".equals(order.getExpandJson())) {
            throw new ForbiddenException("无权关闭该券支付单");
        }
        if (PayStatus.TRADE_CLOSED.equalsValue(order.getStatus())) return;
        if (!PayStatus.WAIT_BUYER_PAY.equalsValue(order.getStatus())) {
            throw new BizIllegalException("支付单已支付，不能取消");
        }
        boolean closed = orders.lambdaUpdate().set(PayOrder::getStatus, PayStatus.TRADE_CLOSED.getValue())
                .eq(PayOrder::getId, order.getId())
                .eq(PayOrder::getStatus, PayStatus.WAIT_BUYER_PAY.getValue()).update();
        if (!closed) throw new BizIllegalException("支付状态变化，请刷新后重试");
    }
}

package com.hmall.pay.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmall.api.client.TradeClient;
import com.hmall.api.client.UserClient;
import com.hmall.api.client.VoucherOrderClient;
import com.hmall.api.dto.OrderSummaryDTO;
import com.hmall.common.exception.BadRequestException;
import com.hmall.common.exception.BizIllegalException;
import com.hmall.common.exception.ForbiddenException;
import com.hmall.common.utils.BeanUtils;
import com.hmall.common.utils.UserContext;
import com.hmall.pay.domain.dto.PayApplyDTO;
import com.hmall.pay.domain.dto.PayOrderFormDTO;
import com.hmall.pay.domain.po.PayOrder;
import com.hmall.pay.enums.PayStatus;
import com.hmall.pay.mapper.PayOrderMapper;
import com.hmall.pay.service.IPayOrderService;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * <p>
 * 支付订单 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2023-05-16
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PayOrderServiceImpl extends ServiceImpl<PayOrderMapper, PayOrder> implements IPayOrderService {

    private final UserClient userClient;

    private final TradeClient tradeClient;

    private final VoucherOrderClient voucherOrderClient;

    private final RabbitTemplate rabbitTemplate;
    private static final String COMMERCE_SOURCE = "{\"source\":\"commerce\"}";
    private static final String VOUCHER_SOURCE = "{\"source\":\"voucher\"}";
    @Override
    public String applyPayOrder(PayApplyDTO applyDTO) {
        OrderSummaryDTO order = tradeClient.getOrder(applyDTO.getBizOrderNo());
        if (order == null || !UserContext.getUser().equals(order.getUserId())) {
            throw new ForbiddenException("订单不存在或无权支付");
        }
        if (order.getStatus() == null || order.getStatus() != 1
                || order.getTotalFee() == null || !order.getTotalFee().equals(applyDTO.getAmount())) {
            throw new BizIllegalException("订单状态或金额不匹配");
        }
        // 1.幂等性校验
        PayOrder payOrder = checkIdempotent(applyDTO, COMMERCE_SOURCE);
        // 2.返回结果
        return payOrder.getId().toString();
    }

    @Override
    public String applyVoucherPayOrder(Long voucherOrderId) {
        OrderSummaryDTO voucherOrder;
        try {
            voucherOrder = voucherOrderClient.getPaymentSummary(voucherOrderId);
        } catch (FeignException e) {
            if (e.status() == 403 || e.status() == 404) {
                throw new ForbiddenException("券订单不存在或无权支付");
            }
            throw e;
        }
        if (voucherOrder == null || UserContext.getUser() == null
                || !UserContext.getUser().equals(voucherOrder.getUserId())) {
            throw new ForbiddenException("券订单不存在或无权支付");
        }
        if (voucherOrder.getStatus() == null || voucherOrder.getStatus() != 1
                || voucherOrder.getTotalFee() == null || voucherOrder.getTotalFee() < 1) {
            throw new BizIllegalException("券订单状态或金额不允许支付");
        }
        PayApplyDTO applyDTO = PayApplyDTO.builder()
                .bizOrderNo(voucherOrderId)
                .amount(voucherOrder.getTotalFee())
                .payChannelCode("balance")
                .payType(5)
                .orderInfo("本地生活代金券")
                .build();
        return checkIdempotent(applyDTO, VOUCHER_SOURCE).getId().toString();
    }

    @Override
    @Transactional
    public void tryPayOrderByBalance(PayOrderFormDTO payOrderFormDTO) {
        // 1.查询支付单
        PayOrder po = getById(payOrderFormDTO.getId());
        if (po == null || UserContext.getUser() == null
                || !UserContext.getUser().equals(po.getBizUserId())) {
            throw new ForbiddenException("支付单不存在或无权支付");
        }
        // 2.判断状态
        if(!PayStatus.WAIT_BUYER_PAY.equalsValue(po.getStatus())){
            // 订单不是未支付，状态异常
            throw new BizIllegalException("交易已支付或关闭！");
        }
        // 3.尝试扣减余额
        try {
            userClient.deductMoney(payOrderFormDTO.getPw(), po.getAmount());
        } catch (FeignException e) {
            if (e.status() >= 400 && e.status() < 500) {
                throw new BadRequestException("余额支付失败，请检查支付密码和账户余额");
            }
            throw e;
        }
        // 4.修改支付单状态
        boolean success = markPayOrderSuccess(payOrderFormDTO.getId(), LocalDateTime.now());
        if (!success) {
            throw new BizIllegalException("交易已支付或关闭！");
        }

        try {
            String routingKey = VOUCHER_SOURCE.equals(po.getExpandJson())
                    ? "voucher.pay.success" : "pay.success";
            rabbitTemplate.convertAndSend("pay.direct", routingKey, po.getBizOrderNo());
        }catch (Exception e){
           log.error("发送支付状态失败");
        }
       // tradeClient.markOrderPaySuccess(po.getBizOrderNo());
//        Order order = new Order();
//        order.setId(po.getBizOrderNo());
//        order.setStatus(2);
//        order.setPayTime(LocalDateTime.now());
//        orderService.updateById(order);
    }

    public boolean markPayOrderSuccess(Long id, LocalDateTime successTime) {
        return lambdaUpdate()
                .set(PayOrder::getStatus, PayStatus.TRADE_SUCCESS.getValue())
                .set(PayOrder::getPaySuccessTime, successTime)
                .eq(PayOrder::getId, id)
                // 支付状态的乐观锁判断
                .in(PayOrder::getStatus, PayStatus.NOT_COMMIT.getValue(), PayStatus.WAIT_BUYER_PAY.getValue())
                .update();
    }


    private PayOrder checkIdempotent(PayApplyDTO applyDTO, String source) {
        // 1.首先查询支付单
        PayOrder oldOrder = queryByBizOrderNo(applyDTO.getBizOrderNo());
        // 2.判断是否存在
        if (oldOrder == null) {
            // 不存在支付单，说明是第一次，写入新的支付单并返回
            PayOrder payOrder = buildPayOrder(applyDTO, source);
            payOrder.setPayOrderNo(IdWorker.getId());
            save(payOrder);
            return payOrder;
        }
        if (!source.equals(oldOrder.getExpandJson())
                && !(COMMERCE_SOURCE.equals(source) &&
                     (oldOrder.getExpandJson() == null || oldOrder.getExpandJson().isEmpty()))) {
            throw new BizIllegalException("业务订单号已被其他支付类型使用");
        }
        // 3.旧单已经存在，判断是否支付成功
        if (PayStatus.TRADE_SUCCESS.equalsValue(oldOrder.getStatus())) {
            // 已经支付成功，抛出异常
            throw new BizIllegalException("订单已经支付！");
        }
        // 4.旧单已经存在，判断是否已经关闭
        if (PayStatus.TRADE_CLOSED.equalsValue(oldOrder.getStatus())) {
            // 已经关闭，抛出异常
            throw new BizIllegalException("订单已关闭");
        }
        // 5.旧单已经存在，判断支付渠道是否一致
        if (!StringUtils.equals(oldOrder.getPayChannelCode(), applyDTO.getPayChannelCode())) {
            // 支付渠道不一致，需要重置数据，然后重新申请支付单
            PayOrder payOrder = buildPayOrder(applyDTO, source);
            payOrder.setId(oldOrder.getId());
            payOrder.setQrCodeUrl("");
            updateById(payOrder);
            payOrder.setPayOrderNo(oldOrder.getPayOrderNo());
            return payOrder;
        }
        // 6.旧单已经存在，且可能是未支付或未提交，且支付渠道一致，直接返回旧数据
        return oldOrder;
    }

    private PayOrder buildPayOrder(PayApplyDTO payApplyDTO, String source) {
        // 1.数据转换
        PayOrder payOrder = BeanUtils.toBean(payApplyDTO, PayOrder.class);
        // 2.初始化数据
        payOrder.setPayOverTime(LocalDateTime.now().plusMinutes(120L));
        payOrder.setStatus(PayStatus.WAIT_BUYER_PAY.getValue());
        payOrder.setBizUserId(UserContext.getUser());
        payOrder.setExpandJson(source);
        return payOrder;
    }
    public PayOrder queryByBizOrderNo(Long bizOrderNo) {
        return lambdaQuery()
                .eq(PayOrder::getBizOrderNo, bizOrderNo)
                .one();
    }
}

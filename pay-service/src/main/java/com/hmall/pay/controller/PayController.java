package com.hmall.pay.controller;

import com.hmall.common.exception.BizIllegalException;
import com.hmall.common.utils.BeanUtils;
import com.hmall.common.utils.UserContext;
import com.hmall.pay.domain.po.PayOrder;
import com.hmall.pay.domain.dto.PayApplyDTO;
import com.hmall.pay.domain.dto.PayOrderFormDTO;
import com.hmall.pay.domain.vo.PayOrderVO;
import com.hmall.pay.enums.PayType;
import com.hmall.pay.service.IPayOrderService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Api(tags = "支付相关接口")
@RestController
@RequestMapping("pay-orders")
@RequiredArgsConstructor
public class PayController {

    private final IPayOrderService payOrderService;

    @ApiOperation("查询支付单")
    @GetMapping
    public List<PayOrderVO> queryPayOrders()
    {
        return BeanUtils.copyList(payOrderService.lambdaQuery()
                .eq(PayOrder::getBizUserId, UserContext.getUser()).list(), PayOrderVO.class);
    }
    @ApiOperation("生成支付单")
    @PostMapping
    public String applyPayOrder(@RequestBody PayApplyDTO applyDTO){
        if(!PayType.BALANCE.equalsValue(applyDTO.getPayType())){
            // 目前只支持余额支付
            throw new BizIllegalException("抱歉，目前只支持余额支付");
        }
        return payOrderService.applyPayOrder(applyDTO);
    }

    @ApiOperation("为本地生活券订单生成余额支付单")
    @PostMapping("voucher/{voucherOrderId}")
    public String applyVoucherPayOrder(@PathVariable("voucherOrderId") Long voucherOrderId) {
        return payOrderService.applyVoucherPayOrder(voucherOrderId);
    }

    @ApiOperation("已支付且未核销的到店券原路退回余额")
    @PostMapping("voucher/{voucherOrderId}/refund")
    public void refundVoucherPayOrder(@PathVariable("voucherOrderId") Long voucherOrderId) {
        payOrderService.refundVoucherPayOrder(voucherOrderId);
    }

    @ApiOperation("尝试基于用户余额支付")
    @ApiImplicitParam(value = "支付单id", name = "id")
    @PostMapping("{id}")
    public void tryPayOrderByBalance(@PathVariable("id") Long id, @RequestBody PayOrderFormDTO payOrderFormDTO){
        payOrderFormDTO.setId(id);
        payOrderService.tryPayOrderByBalance(payOrderFormDTO);
    }
}

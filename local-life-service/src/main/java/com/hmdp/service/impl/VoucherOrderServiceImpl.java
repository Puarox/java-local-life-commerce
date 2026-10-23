package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmall.common.utils.UserContext;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private RedisIdWorker redisIdWorker;

    @Override
    public Result sckillVoucher(Long voucherId) {
        Long userId = UserContext.getUser();
        if (userId == null) {
            return Result.fail("请先登录");
        }

        RLock lock = redissonClient.getLock("voucher:order:" + userId);
        boolean acquired = false;
        try {
            // Use Redisson's watchdog lease so a slow transaction cannot outlive a fixed lease.
            acquired = lock.tryLock();
            if (!acquired) {
                return Result.fail("正在处理该用户的领券请求");
            }
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId, userId);
        } catch (DuplicateKeyException e) {
            // The database uniqueness constraint closes races across service instances.
            return Result.fail("每位用户限领一张");
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result createVoucherOrder(Long voucherId, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher == null) {
            return Result.fail("优惠券不存在");
        }
        if (now.isBefore(voucher.getBeginTime()) || now.isAfter(voucher.getEndTime())) {
            return Result.fail("当前不在优惠券活动时间内");
        }

        long existingOrderCount = lambdaQuery()
                .eq(VoucherOrder::getUserId, userId)
                .eq(VoucherOrder::getVoucherId, voucherId)
                .count();
        if (existingOrderCount > 0) {
            return Result.fail("每位用户限领一张");
        }

        boolean stockReserved = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!stockReserved) {
            return Result.fail("优惠券库存不足");
        }

        VoucherOrder order = new VoucherOrder();
        order.setId(redisIdWorker.nextId("voucher-order"));
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        order.setPayType(1);
        order.setStatus(1);
        order.setCreateTime(now);
        if (!save(order)) {
            throw new IllegalStateException("保存优惠券订单失败");
        }
        return Result.ok(order.getId());
    }
}

package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hmall.api.dto.OrderSummaryDTO;
import com.hmall.api.client.PayOrderClient;
import com.hmall.common.exception.BadRequestException;
import com.hmall.common.exception.ForbiddenException;
import com.hmall.common.utils.UserContext;
import com.hmdp.dto.Result;
import com.hmdp.dto.VoucherOrderVO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.seata.spring.annotation.GlobalTransactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.security.SecureRandom;

@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private IVoucherService voucherService;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private PayOrderClient payOrderClient;
    private static final SecureRandom REDEEM_RANDOM = new SecureRandom();

    @Override
    public Result buyRegularVoucher(Long voucherId) {
        Long userId = UserContext.getUser();
        if (userId == null) return Result.fail("请先登录");
        Voucher voucher = voucherService.getById(voucherId);
        if (voucher == null || !Integer.valueOf(0).equals(voucher.getType())
                || !Integer.valueOf(1).equals(voucher.getStatus())) {
            return Result.fail("普通代金券不存在或已下架");
        }
        RLock lock = redissonClient.getLock("voucher:order:" + userId);
        boolean acquired = false;
        try {
            acquired = lock.tryLock();
            if (!acquired) return Result.fail("正在处理该用户的购券请求");
            if (lambdaQuery().eq(VoucherOrder::getUserId, userId)
                    .eq(VoucherOrder::getVoucherId, voucherId).count() > 0) {
                return Result.fail("每位用户限购一张");
            }
            VoucherOrder order = new VoucherOrder();
            order.setId(redisIdWorker.nextId("voucher-order"));
            order.setUserId(userId);
            order.setVoucherId(voucherId);
            order.setPayType(1);
            order.setStatus(1);
            order.setCreateTime(LocalDateTime.now());
            if (!save(order)) throw new IllegalStateException("保存券订单失败");
            return Result.ok(order.getId().toString());
        } catch (DuplicateKeyException e) {
            return Result.fail("每位用户限购一张");
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    public Result myOrders(int current) {
        Long userId = UserContext.getUser();
        if (userId == null) return Result.fail("请先登录");
        IPage<VoucherOrderVO> page = getBaseMapper()
                .pageByUser(new Page<>(Math.max(1, current), 10), userId);
        return Result.ok(page.getRecords(), page.getTotal());
    }

    public OrderSummaryDTO getPaymentSummary(Long id) {
        VoucherOrder order = getById(id);
        if (order == null || !order.getUserId().equals(UserContext.getUser())) {
            throw new ForbiddenException("券订单不存在或无权查看");
        }
        Voucher voucher = voucherService.getById(order.getVoucherId());
        if (voucher == null) throw new IllegalStateException("券订单对应的优惠券不存在");
        OrderSummaryDTO summary = new OrderSummaryDTO();
        summary.setId(order.getId());
        summary.setUserId(order.getUserId());
        summary.setTotalFee(Math.toIntExact(voucher.getPayValue()));
        summary.setStatus(order.getStatus());
        return summary;
    }

    public void markPaid(Long id) {
        String code = String.format("%010d", REDEEM_RANDOM.nextInt(1_000_000_000));
        boolean updated = lambdaUpdate()
                .set(VoucherOrder::getStatus, 2)
                .set(VoucherOrder::getPayTime, LocalDateTime.now())
                .set(VoucherOrder::getRedeemCode, code)
                .eq(VoucherOrder::getId, id)
                .eq(VoucherOrder::getStatus, 1)
                .update();
        if (!updated) {
            VoucherOrder order = getById(id);
            if (order == null || (order.getStatus() != 2 && order.getStatus() != 3
                    && order.getStatus() != 6)) {
                throw new IllegalStateException("券订单支付状态更新失败: " + id);
            }
        }
    }

    @GlobalTransactional
    @Transactional(rollbackFor = Exception.class)
    public void cancelUnpaid(Long id) {
        Long userId = UserContext.getUser();
        VoucherOrder order = getById(id);
        if (order == null || userId == null || !userId.equals(order.getUserId())) {
            throw new ForbiddenException("券订单不存在或无权取消");
        }
        if (order.getStatus() != 1) throw new BadRequestException("只有待支付的券可以取消");
        payOrderClient.closeUnpaidVoucher(id);
        boolean changed = lambdaUpdate().set(VoucherOrder::getStatus, 4)
                .eq(VoucherOrder::getId, id).eq(VoucherOrder::getStatus, 1).update();
        if (!changed) throw new BadRequestException("券状态已变化，请刷新后重试");
        Voucher voucher = voucherService.getById(order.getVoucherId());
        if (voucher != null && Integer.valueOf(1).equals(voucher.getType())) {
            boolean restored = seckillVoucherService.update()
                    .setSql("stock = stock + 1")
                    .eq("voucher_id", order.getVoucherId()).update();
            if (!restored) throw new IllegalStateException("秒杀库存恢复失败");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Long redeem(String code) {
        if (code == null || !code.matches("[0-9]{10}")) {
            throw new BadRequestException("请输入十位核销码");
        }
        VoucherOrder order = lambdaQuery().eq(VoucherOrder::getRedeemCode, code).one();
        if (order == null || order.getStatus() != 2) {
            throw new BadRequestException("核销码无效或已使用");
        }
        boolean changed = lambdaUpdate().set(VoucherOrder::getStatus, 3)
                .set(VoucherOrder::getUseTime, LocalDateTime.now())
                .eq(VoucherOrder::getId, order.getId()).eq(VoucherOrder::getStatus, 2).update();
        if (!changed) throw new BadRequestException("券状态已变化，请刷新后重试");
        return order.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void markRefunded(Long id) {
        VoucherOrder order = getById(id);
        if (order == null || !order.getUserId().equals(UserContext.getUser())) {
            throw new ForbiddenException("券订单不存在或无权退款");
        }
        boolean changed = lambdaUpdate().set(VoucherOrder::getStatus, 6)
                .set(VoucherOrder::getRefundTime, LocalDateTime.now())
                .eq(VoucherOrder::getId, id).eq(VoucherOrder::getStatus, 2).update();
        if (!changed) throw new BadRequestException("券已核销或退款状态已变化");
    }

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
        return Result.ok(order.getId().toString());
    }
}

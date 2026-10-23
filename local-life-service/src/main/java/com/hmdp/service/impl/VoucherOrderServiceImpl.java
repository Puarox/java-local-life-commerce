package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import java.util.concurrent.TimeUnit;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;


/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private RedisIdWorker  redisIdWorker;
    @Autowired
    private VoucherServiceImpl voucherServiceImpl;

    @Resource
    private StringRedisTemplate stringRedisTemplate ;

    @Override
    public Result sckillVoucher(Long voucherId){
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        LocalDateTime time = LocalDateTime.now();
        if(!voucher.getBeginTime().isBefore(time) && !voucher.getEndTime().isAfter(time)){
            return Result.fail("sorry but not the time  voucher...");
        }

        if(voucher.getStock() < 1){

            return Result.fail("sorry but not the stock voucher...");
        }
        // 5.一人一单逻辑
        // 5.1.用户id
        Long userId = UserHolder.getUser().getId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 5.2.判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            return Result.fail("用户已经购买过一次！");
        }
        boolean success = seckillVoucherService.update()
                .setSql("stock= stock -1")
                .eq("voucher_id", voucherId).update();
        if (!success) {
            //扣减库存
            return Result.fail("库存不足！");
        }

        RLock lock = redissonClient.getLock("order:"+userId);


        boolean isLock  = lock.tryLock();
        if(!isLock){
            return Result.fail("sorry but not the lock voucher...");
        }
        try{
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }finally {
            lock.unlock();
        }
    }
    @Override
    @Transactional
    public Result createVoucherOrder( Long voucherId){

    Long userId = 32452343L;

    int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
    if(count > 0){
        return Result.fail("sorry but fail...");
    }

    boolean sucess = seckillVoucherService.update()
            .setSql("stock = stock -1")
            .eq("voucher_id", voucherId)
            .gt("stock", 0)
            .update();

    if (!sucess){
        return Result.fail("sorry but fail...");
    }
    VoucherOrder voucherOrder = new VoucherOrder();
    long orderId = redisIdWorker.nextId("order");
    voucherOrder.setId(orderId);
    //TODO:完成用户登录中的用户获取等等

    voucherOrder.setUserId(userId);

    voucherOrder.setVoucherId(voucherId);

    save(voucherOrder);
return Result.ok(orderId);
}

}

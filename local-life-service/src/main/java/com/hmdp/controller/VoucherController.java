package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IVoucherService;
import com.hmdp.service.IShopService;
import com.hmall.common.exception.BadRequestException;
import com.hmall.common.exception.ForbiddenException;
import com.hmall.common.utils.UserContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher")
public class VoucherController {

    @Resource
    private IVoucherService voucherService;
    @Resource
    private IShopService shopService;
    @Value("${LOCAL_LIFE_ADMIN_USER_ID:0}")
    private Long adminUserId;

    /**
     * 新增普通券
     * @param voucher 优惠券信息
     * @return 优惠券id
     */
    @PostMapping
    public Result addVoucher(@RequestBody Voucher voucher) {
        requireAdmin();
        validateVoucher(voucher);
        voucher.setType(0).setStatus(1);
        voucherService.save(voucher);
        return Result.ok(voucher.getId());
    }

    /**
     * 新增秒杀券
     * @param voucher 优惠券信息，包含秒杀信息
     * @return 优惠券id
     */
    @PostMapping("seckill")
    public Result addSeckillVoucher(@RequestBody Voucher voucher) {
        requireAdmin();
        validateVoucher(voucher);
        if (voucher.getStock() == null || voucher.getStock() < 1
                || voucher.getBeginTime() == null || voucher.getEndTime() == null
                || !voucher.getEndTime().isAfter(voucher.getBeginTime())
                || !voucher.getEndTime().isAfter(LocalDateTime.now())) {
            throw new BadRequestException("请填写有效的活动时间和正数库存");
        }
        voucher.setType(1).setStatus(1);
        voucherService.addSeckillVoucher(voucher);
        return Result.ok(voucher.getId());
    }

    /**
     * 查询店铺的优惠券列表
     * @param shopId 店铺id
     * @return 优惠券列表
     */
    @GetMapping("/list/{shopId}")
    public Result queryVoucherOfShop(@PathVariable("shopId") Long shopId) {
       return voucherService.queryVoucherOfShop(shopId);
    }

    @GetMapping("/active")
    public Result activeActivities() {
        return voucherService.activeActivities();
    }

    @PutMapping("/{id}/status")
    public Result changeStatus(@PathVariable("id") Long id, @RequestParam("status") int status) {
        requireAdmin();
        if (status != 1 && status != 2) throw new BadRequestException("券状态只能是上架或下架");
        boolean updated = voucherService.lambdaUpdate()
                .set(Voucher::getStatus, status)
                .eq(Voucher::getId, id)
                .update();
        if (!updated) throw new BadRequestException("优惠券不存在");
        return Result.ok();
    }

    private void requireAdmin() {
        if (!adminUserId.equals(UserContext.getUser())) {
            throw new ForbiddenException("无权创建优惠券");
        }
    }

    private void validateVoucher(Voucher voucher) {
        if (voucher.getShopId() == null || shopService.getById(voucher.getShopId()) == null) {
            throw new BadRequestException("商户不存在");
        }
        if (voucher.getTitle() == null || voucher.getTitle().trim().isEmpty()
                || voucher.getTitle().length() > 255
                || voucher.getPayValue() == null || voucher.getPayValue() < 1
                || voucher.getPayValue() > Integer.MAX_VALUE
                || voucher.getActualValue() == null || voucher.getActualValue() <= voucher.getPayValue()) {
            throw new BadRequestException("请填写有效的券标题和金额");
        }
    }
}

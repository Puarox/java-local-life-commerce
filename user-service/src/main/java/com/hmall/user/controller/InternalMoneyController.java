package com.hmall.user.controller;

import com.hmall.common.exception.BadRequestException;
import com.hmall.common.utils.UserContext;
import com.hmall.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/users/money")
@RequiredArgsConstructor
public class InternalMoneyController {
    private final UserMapper users;

    @PutMapping("/credit")
    public void credit(@RequestParam("amount") Integer amount) {
        Long userId = UserContext.getUser();
        if (userId == null || amount == null || amount < 1) {
            throw new BadRequestException("退款用户或金额无效");
        }
        if (users.creditMoney(userId, amount) != 1) {
            throw new BadRequestException("退款账户不存在");
        }
    }
}

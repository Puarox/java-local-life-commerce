package com.hmdp.controller;

import com.hmall.api.client.UserClient;
import com.hmall.api.dto.UserProfileDTO;
import com.hmall.common.utils.UserContext;
import com.hmdp.dto.Result;
import com.hmdp.entity.UserInfo;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final IUserService signService;
    private final IUserInfoService userInfoService;
    private final UserClient userClient;

    @GetMapping("/me")
    public Result me() {
        Long userId = UserContext.getUser();
        if (userId == null) {
            return Result.fail("请先登录");
        }
        return Result.ok(userClient.getProfile(userId));
    }

    @GetMapping("/{id}")
    public Result profile(@PathVariable("id") Long userId) {
        UserProfileDTO profile = userClient.getProfile(userId);
        return profile == null ? Result.fail("用户不存在") : Result.ok(profile);
    }

    @PostMapping("/logout")
    public Result logout() {
        // JWT is stateless; the client clears its token after this acknowledgement.
        return Result.ok();
    }

    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId) {
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        UserProfileDTO profile = userClient.getProfile(userId);
        return Result.ok(new LocalUserProfileDTO(profile, info));
    }

    @PostMapping("/sign")
    public Result sign() {
        return signService.sign();
    }

    @GetMapping("/sign/count")
    public Result signCount() {
        return signService.signCount();
    }

    private static class LocalUserProfileDTO {
        private final UserProfileDTO account;
        private final UserInfo localLife;

        private LocalUserProfileDTO(UserProfileDTO account, UserInfo localLife) {
            this.account = account;
            this.localLife = localLife;
        }

        public UserProfileDTO getAccount() { return account; }
        public UserInfo getLocalLife() { return localLife; }
    }
}

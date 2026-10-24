package com.hmdp.controller;

import com.hmall.api.client.UserClient;
import com.hmall.api.dto.UserProfileDTO;
import com.hmall.common.utils.UserContext;
import com.hmdp.dto.Result;
import com.hmdp.dto.UpdateUserInfoDTO;
import com.hmall.common.exception.BadRequestException;
import com.hmdp.entity.UserInfo;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

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
            info = new UserInfo().setUserId(userId);
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        UserProfileDTO profile = userClient.getProfile(userId);
        return Result.ok(new LocalUserProfileDTO(profile, info));
    }

    @PutMapping("/info")
    public Result updateInfo(@RequestBody UpdateUserInfoDTO update) {
        Long userId = UserContext.getUser();
        if (userId == null) throw new BadRequestException("请先登录");
        String city = update.getCity() == null ? "" : update.getCity().trim();
        String introduce = update.getIntroduce() == null ? "" : update.getIntroduce().trim();
        if (city.length() > 64) throw new BadRequestException("城市名称不能超过 64 字");
        if (introduce.length() > 128) throw new BadRequestException("个人介绍不能超过 128 字");
        if (update.getBirthday() != null && update.getBirthday().isAfter(LocalDate.now())) {
            throw new BadRequestException("生日不能晚于今天");
        }
        UserInfo info = userInfoService.getById(userId);
        boolean exists = info != null;
        if (!exists) info = new UserInfo().setUserId(userId);
        info.setCity(city).setIntroduce(introduce)
                .setGender(update.getGender()).setBirthday(update.getBirthday());
        if (exists) userInfoService.updateById(info);
        else userInfoService.save(info);
        return info(userId);
    }

    @PostMapping("/sign")
    public Result sign() {
        return signService.sign();
    }

    @GetMapping("/sign/count")
    public Result signCount() {
        return signService.signCount();
    }

    @Getter
    private static class LocalUserProfileDTO {
        private final Long id;
        private final String username;
        private final String nickName;
        private final String icon;
        private final Long userId;
        private final String city;
        private final String introduce;
        private final Integer fans;
        private final Integer followee;
        private final Boolean gender;
        private final LocalDate birthday;
        private final Integer credits;
        private final Boolean level;

        private LocalUserProfileDTO(UserProfileDTO account, UserInfo localLife) {
            this.id = account == null ? localLife.getUserId() : account.getId();
            this.username = account == null ? null : account.getUsername();
            this.nickName = account == null ? null : account.getNickName();
            this.icon = account == null ? null : account.getIcon();
            this.userId = localLife.getUserId();
            this.city = localLife.getCity();
            this.introduce = localLife.getIntroduce();
            this.fans = localLife.getFans();
            this.followee = localLife.getFollowee();
            this.gender = localLife.getGender();
            this.birthday = localLife.getBirthday();
            this.credits = localLife.getCredits();
            this.level = localLife.getLevel();
        }
    }
}

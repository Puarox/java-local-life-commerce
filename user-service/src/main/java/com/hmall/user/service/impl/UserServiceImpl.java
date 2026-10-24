package com.hmall.user.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmall.common.exception.BadRequestException;
import com.hmall.common.exception.ForbiddenException;
import com.hmall.common.utils.JwtTool;
import com.hmall.common.utils.UserContext;
import com.hmall.user.config.JwtProperties;
import com.hmall.user.domain.dto.LoginFormDTO;
import com.hmall.user.domain.dto.UpdateProfileDTO;
import com.hmall.user.domain.po.User;
import com.hmall.user.domain.vo.UserLoginVO;
import com.hmall.user.enums.UserStatus;
import com.hmall.user.mapper.UserMapper;
import com.hmall.user.service.IUserService;
import cn.hutool.core.util.RandomUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.util.Assert;

import java.util.concurrent.TimeUnit;
import java.time.LocalDateTime;

/**
 * <p>
 * 用户表 服务实现类
 * </p>
 *
 * @author 虎哥
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private final PasswordEncoder passwordEncoder;

    private final JwtTool jwtTool;

    private final JwtProperties jwtProperties;

    private final StringRedisTemplate redisTemplate;

    private static final String LOGIN_CODE_PREFIX = "login:code:";

    @Override
    public UserLoginVO login(LoginFormDTO loginDTO) {
        User user;
        if (StringUtils.hasText(loginDTO.getPhone())) {
            String key = LOGIN_CODE_PREFIX + loginDTO.getPhone();
            String expectedCode = redisTemplate.opsForValue().get(key);
            if (!StringUtils.hasText(expectedCode) || !expectedCode.equals(loginDTO.getCode())) {
                throw new BadRequestException("手机号或验证码错误");
            }
            user = lambdaQuery().eq(User::getPhone, loginDTO.getPhone()).one();
            if (user == null) {
                LocalDateTime now = LocalDateTime.now();
                user = new User()
                        .setUsername(loginDTO.getPhone())
                        .setPhone(loginDTO.getPhone())
                        .setNickName("用户" + loginDTO.getPhone().substring(Math.max(0, loginDTO.getPhone().length() - 4)))
                        .setIcon("")
                        .setPassword(passwordEncoder.encode(RandomUtil.randomString(32)))
                        .setBalance(0)
                        .setStatus(UserStatus.NORMAL)
                        .setCreateTime(now)
                        .setUpdateTime(now);
                save(user);
            }
            redisTemplate.delete(key);
        } else {
            String username = loginDTO.getUsername();
            String password = loginDTO.getPassword();
            if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
                throw new BadRequestException("请提供用户名密码或手机号验证码");
            }
            user = lambdaQuery().eq(User::getUsername, username).one();
            Assert.notNull(user, "用户名或密码错误");
        }
        // 3.校验是否禁用
        if (user.getStatus() == UserStatus.FROZEN) {
            throw new ForbiddenException("用户被冻结");
        }
        // 4.校验密码
        if (!StringUtils.hasText(loginDTO.getPhone())
                && !passwordEncoder.matches(loginDTO.getPassword(), user.getPassword())) {
            throw new BadRequestException("用户名或密码错误");
        }
        // 5.生成TOKEN
        String token = jwtTool.createToken(user.getId(), jwtProperties.getTokenTTL());
        // 6.封装VO返回
        UserLoginVO vo = new UserLoginVO();
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setBalance(user.getBalance());
        vo.setToken(token);
        return vo;
    }

    @Override
    public void sendCode(String phone) {
        if (!StringUtils.hasText(phone) || !phone.matches("\\+?[0-9]{7,15}")) {
            throw new BadRequestException("手机号格式错误");
        }
        String code = RandomUtil.randomNumbers(6);
        redisTemplate.opsForValue().set(LOGIN_CODE_PREFIX + phone, code, 2, TimeUnit.MINUTES);
        log.info("Development SMS code generated for login; phone suffix={}",
                phone.substring(Math.max(0, phone.length() - 4)));
    }

    @Override
    public com.hmall.api.dto.UserProfileDTO getProfile(Long userId) {
        User user = getById(userId);
        if (user == null) {
            return null;
        }
        com.hmall.api.dto.UserProfileDTO profile = new com.hmall.api.dto.UserProfileDTO();
        profile.setId(user.getId());
        profile.setUsername(user.getUsername());
        profile.setNickName(StringUtils.hasText(user.getNickName()) ? user.getNickName() : user.getUsername());
        profile.setIcon(user.getIcon());
        return profile;
    }

    @Override
    public com.hmall.api.dto.UserProfileDTO updateProfile(UpdateProfileDTO profile) {
        Long userId = UserContext.getUser();
        if (userId == null) throw new BadRequestException("请先登录");
        String nickName = profile.getNickName() == null ? "" : profile.getNickName().trim();
        String icon = profile.getIcon() == null ? "" : profile.getIcon().trim();
        if (nickName.isEmpty() || nickName.length() > 32) {
            throw new BadRequestException("昵称长度应为 1 到 32 字");
        }
        if (icon.length() > 255) throw new BadRequestException("头像地址过长");
        boolean updated = lambdaUpdate()
                .set(User::getNickName, nickName)
                .set(User::getIcon, icon)
                .set(User::getUpdateTime, LocalDateTime.now())
                .eq(User::getId, userId)
                .update();
        if (!updated) throw new BadRequestException("账号不存在");
        return getProfile(userId);
    }

    @Override
    public void deductMoney(String pw, Integer totalFee) {
        log.info("开始扣款");
        // 1.校验密码
        User user = getById(UserContext.getUser());
        if(user == null || !passwordEncoder.matches(pw, user.getPassword())){
            throw new BadRequestException("支付密码错误");
        }

        // 2.尝试扣款
        try {
            int updated = baseMapper.updateMoney(UserContext.getUser(), totalFee);
            if (updated != 1) {
                throw new BadRequestException("账户余额不足");
            }
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("扣款失败，请稍后重试", e);
        }
        log.info("扣款成功");
    }
}

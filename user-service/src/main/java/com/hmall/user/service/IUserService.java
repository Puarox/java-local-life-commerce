package com.hmall.user.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmall.user.domain.dto.LoginFormDTO;
import com.hmall.user.domain.po.User;
import com.hmall.user.domain.vo.UserLoginVO;
import com.hmall.api.dto.UserProfileDTO;


/**
 * <p>
 * 用户表 服务类
 * </p>
 *
 * @author 虎哥
 * @since 2023-05-05
 */
public interface IUserService extends IService<User> {

    UserLoginVO login(LoginFormDTO loginFormDTO);

    void sendCode(String phone);

    UserProfileDTO getProfile(Long userId);

    void deductMoney(String pw, Integer totalFee);
}

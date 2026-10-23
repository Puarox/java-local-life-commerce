package com.hmdp.utils;

import com.hmall.common.utils.UserContext;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        if (UserContext.getUser() == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED) ;
            return false ;
        }
        return true;

    }

}

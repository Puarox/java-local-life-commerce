package com.hmall.api.client;

import com.hmall.api.config.DefaultFeignConfig;
import com.hmall.api.dto.UserProfileDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(value = "service-user",configuration = DefaultFeignConfig.class)
public interface UserClient {
    @GetMapping("/users/profile/{id}")
    UserProfileDTO getProfile(@PathVariable("id") Long id);

    @PostMapping("/users/code")
    void sendCode(@RequestParam("phone") String phone);

    @PutMapping("/users/money/deduct")
    void deductMoney(@RequestParam("pw") String pw, @RequestParam("amount") Integer amount);

    @PutMapping("/internal/users/money/credit")
    void creditMoney(@RequestParam("amount") Integer amount);
}

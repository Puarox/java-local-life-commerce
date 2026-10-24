package com.hmall.item;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.hmall.item.mapper")
@SpringBootApplication(scanBasePackages = {"com.hmall.item", "com.hmall.common.advice"})
public class  ItemApplication{
    public static void main(String[] args) {
        SpringApplication.run(ItemApplication.class, args);
    }
}

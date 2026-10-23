package com.hmdp.utils;


import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;
import java.util.concurrent.TimeUnit;



public class SimpleRedisLock implements SimpleRedisLockInterface {


    private String name ;
    private StringRedisTemplate redisTemplate;
    public SimpleRedisLock(String name, StringRedisTemplate redisTemplate) {
        this.name = name;
        this.redisTemplate = redisTemplate;
    }
    private static final String ID_PREFIX = UUID.randomUUID().toString() + "-";
    private static final String KEY_PREFIX = "lock:";

    @Override
    public boolean tryLock(long timeout)
    {
        String threadId = ID_PREFIX + Thread.currentThread().getId();

        Boolean success = redisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX+name,threadId,timeout, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success) ;
    }
    @Override
    public void unlock()
    {
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        String id = redisTemplate.opsForValue().get(KEY_PREFIX+name);
        if(threadId.equals(id)){
            redisTemplate.delete(KEY_PREFIX+name);
        }
        else{
            return  ;

        }
    }

}

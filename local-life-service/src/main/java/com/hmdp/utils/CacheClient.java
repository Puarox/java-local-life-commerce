package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;

@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final long lockWaitMillis;

    public CacheClient(StringRedisTemplate stringRedisTemplate, RedissonClient redissonClient,
                       @Value("${local-life.cache.shop-lock-wait-ms:5000}") long lockWaitMillis) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.redissonClient = redissonClient;
        this.lockWaitMillis = Math.max(0, lockWaitMillis);
    }

    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    public <R, ID> R queryShopWithThrough(String keyPrefix, ID id, Class<R> type,
                                           Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {
        String key = keyPrefix + id;
        String cached = stringRedisTemplate.opsForValue().get(key);
        if (cached != null) return decode(cached, type);

        R value = dbFallback.apply(id);
        cacheValue(key, value, time, timeUnit);
        return value;
    }

    public <R, ID> R queryShopWithMutex(String keyPrefix, ID id, Class<R> type,
                                        Function<ID, R> dbFallback, Long time, TimeUnit timeUnit,
                                        String lockPrefix) {
        String key = keyPrefix + id;
        String cached = stringRedisTemplate.opsForValue().get(key);
        if (cached != null) return decode(cached, type);

        RLock lock = redissonClient.getLock(lockPrefix + id);
        boolean acquired = false;
        try {
            // Redisson's watchdog extends the lease while this thread owns the lock.
            acquired = lock.tryLock(lockWaitMillis, TimeUnit.MILLISECONDS);
            if (!acquired) {
                // The owner may have filled the cache just before our bounded wait ended.
                cached = stringRedisTemplate.opsForValue().get(key);
                if (cached != null) return decode(cached, type);
                // Bound request-thread waiting if Redis lock ownership is stuck.
                R value = dbFallback.apply(id);
                cacheValue(key, value, time, timeUnit);
                return value;
            }

            // Another request may have populated the key while we waited for the lock.
            cached = stringRedisTemplate.opsForValue().get(key);
            if (cached != null) return decode(cached, type);

            R value = dbFallback.apply(id);
            cacheValue(key, value, time, timeUnit);
            return value;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for shop cache lock", e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private <R> R decode(String cached, Class<R> type) {
        return StrUtil.isBlank(cached) ? null : JSONUtil.toBean(cached, type);
    }

    private void cacheValue(String key, Object value, Long time, TimeUnit timeUnit) {
        if (value == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
        } else {
            set(key, value, time, timeUnit);
        }
    }
}

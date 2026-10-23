package com.hmdp.utils;

public interface SimpleRedisLockInterface {
    boolean tryLock(long timeout);

    void unlock();
}

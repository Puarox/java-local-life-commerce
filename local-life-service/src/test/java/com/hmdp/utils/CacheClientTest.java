package com.hmdp.utils;

import com.hmdp.entity.Shop;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CacheClientTest {

    @Test
    void concurrentMissLoadsDatabaseOnceAndReleasesLock() throws Exception {
        Fixture fixture = new Fixture(2_000);
        int callers = 32;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger databaseLoads = new AtomicInteger();
        try {
            List<Future<Shop>> results = new ArrayList<>(callers);
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(3, TimeUnit.SECONDS));
                    return fixture.client.queryShopWithMutex("cache:shop:", 7L, Shop.class, id -> {
                        databaseLoads.incrementAndGet();
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                        return new Shop().setId(id).setName("Concurrent Shop");
                    }, 30L, TimeUnit.MINUTES, "lock:shop:");
                }));
            }
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            start.countDown();
            for (Future<Shop> result : results) {
                assertEquals("Concurrent Shop", result.get(5, TimeUnit.SECONDS).getName());
            }
            assertEquals(1, databaseLoads.get(), "only the lock owner should load the missing key");
            assertTrue(fixture.unlocks.get() > 0, "every caller that acquires the lock must release it");
            assertTrue(fixture.unlocks.get() <= callers);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void timeoutDoesNotUnlockAnotherThreadsLock() throws Exception {
        Fixture fixture = new Fixture(20);
        fixture.lock.lock();
        AtomicInteger databaseLoads = new AtomicInteger();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Shop> result = pool.submit(() -> fixture.client.queryShopWithMutex(
                    "cache:shop:", 8L, Shop.class,
                    id -> { databaseLoads.incrementAndGet(); return new Shop().setId(id); },
                    30L, TimeUnit.MINUTES, "lock:shop:"));
            assertEquals(8L, result.get(2, TimeUnit.SECONDS).getId());
            assertEquals(1, databaseLoads.get());
            assertEquals(0, fixture.unlocks.get(), "a timed-out waiter must never release the owner's lock");
            assertTrue(fixture.lock.isLocked(), "the original lock owner must retain the lock");
        } finally {
            fixture.lock.unlock();
            pool.shutdownNow();
        }
    }

    @Test
    void missingValuesAreNegativeCached() {
        Fixture fixture = new Fixture(100);
        AtomicInteger databaseLoads = new AtomicInteger();
        assertNull(fixture.client.queryShopWithMutex("cache:shop:", 99L, Shop.class,
                id -> { databaseLoads.incrementAndGet(); return null; }, 30L, TimeUnit.MINUTES, "lock:shop:"));
        assertNull(fixture.client.queryShopWithMutex("cache:shop:", 99L, Shop.class,
                id -> { databaseLoads.incrementAndGet(); return null; }, 30L, TimeUnit.MINUTES, "lock:shop:"));
        assertEquals(1, databaseLoads.get());
    }

    @Test
    void interruptedWaitRestoresInterruptAndDoesNotUnlockOwner() throws Exception {
        Fixture fixture = new Fixture(5_000);
        fixture.lock.lock();
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        Thread waiter = new Thread(() -> {
            started.countDown();
            try {
                fixture.client.queryShopWithMutex("cache:shop:", 10L, Shop.class,
                        id -> new Shop().setId(id), 30L, TimeUnit.MINUTES, "lock:shop:");
            } catch (Throwable e) {
                failure.set(e);
                interruptRestored.set(Thread.currentThread().isInterrupted());
            }
        });
        try {
            waiter.start();
            assertTrue(started.await(1, TimeUnit.SECONDS));
            Thread.sleep(50);
            waiter.interrupt();
            waiter.join(2_000);
            assertFalse(waiter.isAlive(), "interrupted lock wait should exit promptly");
            assertInstanceOf(IllegalStateException.class, failure.get());
            assertTrue(interruptRestored.get(), "interrupt status should be restored before throwing");
            assertEquals(0, fixture.unlocks.get());
            assertTrue(fixture.lock.isLocked());
        } finally {
            fixture.lock.unlock();
            waiter.interrupt();
        }
    }

    private static final class Fixture {
        private final Map<String, String> values = new ConcurrentHashMap<>();
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicInteger unlocks = new AtomicInteger();
        private final CacheClient client;

        @SuppressWarnings("unchecked")
        private Fixture(long waitMillis) {
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            ValueOperations<String, String> operations = mock(ValueOperations.class);
            RedissonClient redisson = mock(RedissonClient.class);
            RLock redissonLock = mock(RLock.class);
            when(redis.opsForValue()).thenReturn(operations);
            when(operations.get(anyString())).thenAnswer(invocation -> values.get(invocation.getArgument(0)));
            doAnswer(invocation -> {
                values.put(invocation.getArgument(0), invocation.getArgument(1));
                return null;
            }).when(operations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
            when(redisson.getLock(anyString())).thenReturn(redissonLock);
            try {
                when(redissonLock.tryLock(anyLong(), any(TimeUnit.class))).thenAnswer(invocation ->
                        lock.tryLock(invocation.getArgument(0), invocation.getArgument(1)));
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            when(redissonLock.isHeldByCurrentThread()).thenAnswer(invocation -> lock.isHeldByCurrentThread());
            doAnswer(invocation -> {
                unlocks.incrementAndGet();
                lock.unlock();
                return null;
            }).when(redissonLock).unlock();
            client = new CacheClient(redis, redisson, waitMillis);
        }
    }
}

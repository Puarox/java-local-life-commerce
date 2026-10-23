package com.hmall.common.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UserContextTest {

    @AfterEach
    void clearContext() {
        UserContext.removeUser();
    }

    @Test
    void storesAndRemovesCurrentUser() {
        UserContext.setUser(42L);

        assertEquals(42L, UserContext.getUser());

        UserContext.removeUser();
        assertNull(UserContext.getUser());
    }

    @Test
    void doesNotLeakUserAcrossThreads() throws InterruptedException {
        UserContext.setUser(42L);
        AtomicReference<Long> otherThreadUser = new AtomicReference<>();
        Thread thread = new Thread(() -> otherThreadUser.set(UserContext.getUser()));

        thread.start();
        thread.join();

        assertNull(otherThreadUser.get());
        assertEquals(42L, UserContext.getUser());
    }
}

package com.processor;

import org.springframework.stereotype.Component;

/**
 * 用户上下文，通过 ThreadLocal 在当前线程传递当前处理消息的 userId。
 */
@Component
public class UserContext {

    private final ThreadLocal<String> currentUserId = new ThreadLocal<>();

    public void executeAs(String userId, Runnable action) {
        try {
            currentUserId.set(userId);
            action.run();
        } finally {
            currentUserId.remove();
        }
    }

    public String getCurrentUserId() {
        return currentUserId.get();
    }
}
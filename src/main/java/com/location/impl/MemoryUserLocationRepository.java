package com.location.impl;

import org.springframework.stereotype.Repository;
import com.location.model.UserLocation;
import com.location.repository.UserLocationRepository;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 内存位置存储，带 LRU 容量上限。
 */
@Repository
public class MemoryUserLocationRepository implements UserLocationRepository {

    /** 最多缓存的用户位置数，超出按 LRU 逐出 */
    private static final int MAX_ENTRIES = 1000;

    private final Map<String, UserLocation> locations =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, UserLocation> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    @Override
    public void save(UserLocation location) {
        if (location == null) {
            throw new RuntimeException("位置不能为空");
        }

        if (location.userId() == null || location.userId().isBlank()) {
            throw new RuntimeException("用户ID不能为空");
        }

        locations.put(location.userId(), location);
    }

    @Override
    public Optional<UserLocation> findByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(locations.get(userId));
    }
}
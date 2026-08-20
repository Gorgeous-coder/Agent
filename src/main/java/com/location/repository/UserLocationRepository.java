package com.location.repository;

import com.location.model.UserLocation;

import java.util.Optional;

/**
 * 用户位置仓库。
 * Tool只依赖这个接口，不关心数据最终存放在内存还是MySQL。
 */
public interface UserLocationRepository {

    /**
     * 保存或覆盖用户当前位置。
     */
    void save(UserLocation location);

    /**
     * 根据微信用户ID查询最近保存的位置。
     */
    Optional<UserLocation> findByUserId(String userId);
}

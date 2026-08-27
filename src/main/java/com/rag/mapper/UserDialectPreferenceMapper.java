package com.rag.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserDialectPreferenceMapper {

    @Select("SELECT dialect_type FROM user_dialect_preference WHERE user_id = #{userId}")
    String findDialectByUserId(@Param("userId") String userId);

    @Update("""
        INSERT OR REPLACE INTO user_dialect_preference (user_id, dialect_type, updated_at)
        VALUES (#{userId}, #{dialectType}, CURRENT_TIMESTAMP)
    """)
    void upsertPreference(@Param("userId") String userId, @Param("dialectType") String dialectType);
}
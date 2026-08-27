package com;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.rag.mapper")  // ← 添加这行，扫描 Mapper 包
public class YkdApplication {
    public static void main(String[] args) {
        SpringApplication.run(YkdApplication.class, args);
    }
}
package com.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 存储配置：统一管理所有运行时产物（备忘、二维码、图片、语音等）的根目录。
 *
 * <p>只配置一个根目录，子目录通过 {@code root.resolve(...)} 在代码里派生，避免配置冗余。</p>
 *
 * @ConfigurationProperties注解注入的变量是下方属性中的root，等号右边是默认值
 */
@Component
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {

    /** 存储根目录（相对路径基于工作目录解析） */
    private String root = "./data";

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }

    /** 根目录绝对路径（normalize 去掉 "./" 等冗余段）
     *
     * 把传入的路径字符串`root`，转换为**绝对路径**，并且清除路径里的`.`、`..`这类相对路径片段，返回处理完毕的`Path`对象
     */
    public Path rootPath() {
        return Path.of(root).toAbsolutePath().normalize();
    }



    /** 备忘目录：{root}/memos
     *
     * Java NIO Path 的 `resolve()`：把传入字符串当作**子路径追加到当前 Path 后面**。
     */
    public Path memoDir() {
        return rootPath().resolve("memos");
    }

    /** 二维码目录：{root}/qr */
    public Path qrDir() {
        return rootPath().resolve("qr");
    }
}

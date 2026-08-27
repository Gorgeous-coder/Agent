package com.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 本地磁盘存储实现：文件保存在 {@link StorageProperties} 配置的根目录下。
 */
@Slf4j
@Service
public class LocalFileStorageService implements FileStorageService {

    private final StorageProperties props;

    public LocalFileStorageService(StorageProperties props) {
        this.props = props;
    }

    @Override
    public String save(byte[] data, String subDir, String filename) {
        try {
            Path dir = props.rootPath().resolve(subDir);
            Files.createDirectories(dir);   // 目录不存在时自动创建
            Path target = dir.resolve(filename);
            Files.write(target, data);
            log.info("[Storage] 保存文件: {}", target.toAbsolutePath());
            return target.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new RuntimeException("保存文件失败: " + subDir + "/" + filename, e);
        }
    }

    @Override
    public byte[] load(String path) {
        try {
            return Files.readAllBytes(Path.of(path));
        } catch (IOException e) {
            throw new RuntimeException("读取文件失败: " + path, e);
        }
    }

    @Override
    public void delete(String path) {
        try {
            boolean deleted = Files.deleteIfExists(Path.of(path));
            if (deleted) {
                log.info("[Storage] 删除文件: {}", path);
            }
        } catch (IOException e) {
            throw new RuntimeException("删除文件失败: " + path, e);
        }
    }
}

package com.storage;

/**
 * 文件存储抽象：业务代码只依赖本接口，不关心文件存在本地还是对象存储。
 *
 * <p>切换存储后端只需替换实现类（如本地磁盘 / 阿里云 OSS / MinIO），业务代码零改动。</p>
 */
public interface FileStorageService {

    /**
     * 保存文件。
     *
     * @param data     文件字节内容
     * @param subDir   存储根目录下的子目录，如 "audio"、"images"
     * @param filename 文件名
     * @return 可访问的绝对路径或 URL
     */
    String save(byte[] data, String subDir, String filename);

    /**
     * 读取文件内容。
     *
     * @param path 保存时返回的路径
     * @return 文件字节内容
     */
    byte[] load(String path);

    /**
     * 删除文件。
     *
     * @param path 保存时返回的路径
     */
    void delete(String path);
}

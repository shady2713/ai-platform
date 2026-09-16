package com.basicframework.module.infra.framework.file.core.client.local;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_PATH_INVALID;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IORuntimeException;
import cn.hutool.core.util.StrUtil;
import com.basicframework.module.infra.framework.file.core.client.AbstractFileClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 本地文件客户端
 *
 */
public class LocalFileClient extends AbstractFileClient<LocalFileClientConfig> {

    public LocalFileClient(Long id, LocalFileClientConfig config) {
        super(id, config);
    }

    @Override
    protected void doInit() {}

    @Override
    public String upload(byte[] content, String path, String type) {
        Path filePath = getFilePath(path);
        FileUtil.writeBytes(content, filePath.toFile());
        return super.formatFileUrl(config.getDomain(), path);
    }

    @Override
    public void delete(String path) {
        Path filePath = getFilePath(path);
        FileUtil.del(filePath.toFile());
    }

    @Override
    public byte[] getContent(String path) {
        Path filePath = getFilePath(path);
        try {
            return Files.readAllBytes(filePath);
        } catch (NoSuchFileException ex) {
            return null;
        } catch (IOException ex) {
            throw new IORuntimeException(ex);
        }
    }

    /**
     * 解析规范路径；结果必须仍位于 basePath 内，拦截 ../、绝对路径和符号链接穿越。
     *
     * <p>必须使用 NIO {@link Path#resolve} 做拼接：其语义跨平台一致（绝对 path 直接
     * 替换 base，归一化后落在 base 之外被拦截）；{@code new File(base, path)} 在
     * Unix 上会把绝对 path 拼接进 base 目录（{@code /base + /abs -> /base/abs/...}），
     * 绝对路径穿越会在 Linux 部署上绕过校验。
     *
     * @param path 相对文件路径
     * @return 归一化后的绝对文件路径
     */
    private Path getFilePath(String path) {
        if (StrUtil.isEmpty(path)) {
            throw exception(FILE_PATH_INVALID);
        }
        try {
            Path canonicalBase =
                    Paths.get(config.getBasePath()).toFile().getCanonicalFile().toPath();
            Path resolved =
                    canonicalBase.resolve(path).toFile().getCanonicalFile().toPath();
            if (!resolved.startsWith(canonicalBase)) {
                throw exception(FILE_PATH_INVALID);
            }
            return resolved;
        } catch (IOException ex) {
            throw new IORuntimeException(ex);
        }
    }
}

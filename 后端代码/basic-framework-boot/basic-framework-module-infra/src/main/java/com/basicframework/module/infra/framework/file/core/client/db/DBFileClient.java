package com.basicframework.module.infra.framework.file.core.client.db;

import cn.hutool.core.collection.CollUtil;
import com.basicframework.module.infra.dal.dataobject.file.FileContentDO;
import com.basicframework.module.infra.dal.mysql.file.FileContentMapper;
import com.basicframework.module.infra.framework.file.core.client.AbstractFileClient;
import java.util.Comparator;
import java.util.List;

/**
 * 基于 DB 存储的文件客户端的配置类
 *
 */
public class DBFileClient extends AbstractFileClient<DBFileClientConfig> {

    private final FileContentMapper fileContentMapper;

    public DBFileClient(Long id, DBFileClientConfig config, FileContentMapper fileContentMapper) {
        super(id, config);
        this.fileContentMapper = fileContentMapper;
    }

    @Override
    protected void doInit() {}

    @Override
    public String upload(byte[] content, String path, String type) {
        FileContentDO contentDO =
                new FileContentDO().setConfigId(getId()).setPath(path).setContent(content);
        fileContentMapper.insert(contentDO);
        // 拼接返回路径
        return super.formatFileUrl(config.getDomain(), path);
    }

    @Override
    public void delete(String path) {
        fileContentMapper.deleteByConfigIdAndPath(getId(), path);
    }

    @Override
    public byte[] getContent(String path) {
        List<FileContentDO> list = fileContentMapper.selectListByConfigIdAndPath(getId(), path);
        if (CollUtil.isEmpty(list)) {
            return null;
        }
        // 不修改 Mapper 返回的集合，取 id 最大的记录，即最后上传的内容。
        return list.stream()
                .max(Comparator.comparing(FileContentDO::getId))
                .map(FileContentDO::getContent)
                .orElse(null);
    }
}

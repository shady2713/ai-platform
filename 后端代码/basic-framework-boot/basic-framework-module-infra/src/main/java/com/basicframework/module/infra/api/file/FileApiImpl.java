package com.basicframework.module.infra.api.file;

import com.basicframework.module.infra.api.file.dto.FileCreateReqDTO;
import com.basicframework.module.infra.api.file.dto.FileDeleteReqDTO;
import com.basicframework.module.infra.api.file.dto.FileReadReqDTO;
import com.basicframework.module.infra.api.file.dto.FileRespDTO;
import com.basicframework.module.infra.api.file.dto.FileSubjectDTO;
import com.basicframework.module.infra.dal.dataobject.file.FileDO;
import com.basicframework.module.infra.service.file.FileAccessPrincipal;
import com.basicframework.module.infra.service.file.FileService;
import com.basicframework.module.infra.service.file.FileUploadPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;

/**
 * 受控文件薄契约实现。
 *
 * <p>跨模块调用方一律不具备管理权限：主体按 {@code canManageFiles=false} 构造，
 * 业务绑定文件的读写删都只由业务授权 Provider 决定。
 */
@Service
@RequiredArgsConstructor
public class FileApiImpl implements FileCommonApi {

    private final FileService fileService;

    @Override
    public Long createFile(FileCreateReqDTO reqDTO) {
        FileSubjectDTO subject = reqDTO.getSubject();
        return fileService.createBusinessFile(
                reqDTO.getContent(),
                reqDTO.getName(),
                reqDTO.getType(),
                reqDTO.getBusinessType(),
                reqDTO.getBusinessId(),
                new FileUploadPrincipal(subject.getUserId(), subject.getUserType()));
    }

    @Override
    public FileRespDTO getFileMeta(FileReadReqDTO reqDTO) {
        return toResp(fileService.getAuthorizedFile(reqDTO.getFileId(), toAccessPrincipal(reqDTO.getSubject())));
    }

    @Override
    @SneakyThrows
    public byte[] getFileContent(FileReadReqDTO reqDTO) {
        // 先按主体授权读取元数据（无权限与不存在同一语义），再从受控存储读取内容
        FileDO file = fileService.getAuthorizedFile(reqDTO.getFileId(), toAccessPrincipal(reqDTO.getSubject()));
        return fileService.getFileContent(file.getConfigId(), file.getPath(), toAccessPrincipal(reqDTO.getSubject()));
    }

    @Override
    @SneakyThrows
    public void deleteFile(FileDeleteReqDTO reqDTO) {
        fileService.deleteBusinessFile(reqDTO.getFileId(), toAccessPrincipal(reqDTO.getSubject()));
    }

    private static FileAccessPrincipal toAccessPrincipal(FileSubjectDTO subject) {
        return new FileAccessPrincipal(subject.getUserId(), subject.getUserType(), false);
    }

    private static FileRespDTO toResp(FileDO file) {
        FileRespDTO respDTO = new FileRespDTO();
        respDTO.setId(file.getId());
        respDTO.setName(file.getName());
        respDTO.setType(file.getType());
        respDTO.setSize(file.getSize());
        respDTO.setBusinessType(file.getBusinessType());
        respDTO.setBusinessId(file.getBusinessId());
        return respDTO;
    }
}

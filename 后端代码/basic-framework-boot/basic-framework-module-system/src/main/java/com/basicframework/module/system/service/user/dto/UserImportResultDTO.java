package com.basicframework.module.system.service.user.dto;

import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 用户导入结果 DTO
 */
@Getter
@AllArgsConstructor
public class UserImportResultDTO {

    /**
     * 创建成功的用户名数组（新用户为禁用状态，需管理员重置密码并启用后激活）
     */
    private final List<String> createUsernames;
    /**
     * 更新成功的用户名数组
     */
    private final List<String> updateUsernames;
    /**
     * 导入失败的用户集合，key 为用户名，value 为失败原因
     */
    private final Map<String, String> failureUsernames;
}

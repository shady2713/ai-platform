package com.basicframework.module.system.service.sms;

import java.time.LocalDateTime;

public record SmsReceiveResultCommand(
        String channelCode,
        Long logId,
        String apiSerialNo,
        Boolean success,
        LocalDateTime receiveTime,
        String apiErrorCode,
        String apiErrorMsg) {}

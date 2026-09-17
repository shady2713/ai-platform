package com.basicframework.module.ai.archfixture.service;

import com.basicframework.module.system.controller.app.ip.vo.AppAreaNodeRespVO;

/**
 * 违例夹具：service 层引用 Controller VO（协议层类型），用于证明规则 I 能变红。
 */
public class VoLeakServiceFixture {

    public AppAreaNodeRespVO toVo() {
        return new AppAreaNodeRespVO();
    }
}

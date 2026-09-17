package com.basicframework.framework.ai.config;

import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.ModelPort;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;

/**
 * AI 提供方装配校验器：启用 AI 能力时的启动期自检。
 *
 * <p>校验三件事，任一失败都抛 {@link IllegalStateException} 让应用启动失败：
 * 恰好存在一个 {@link ModelPort} 实现；该实现声明了非空能力集合；
 * 实现覆盖 {@link AiProperties#getCapabilities()} 声明的全部能力。
 * 错误消息给出稳定、可操作的提示，不含凭据或端点信息。
 */
public class AiProviderValidator implements InitializingBean {

    private final AiProperties properties;

    private final ObjectProvider<ModelPort> modelPorts;

    public AiProviderValidator(AiProperties properties, ObjectProvider<ModelPort> modelPorts) {
        this.properties = properties;
        this.modelPorts = modelPorts;
    }

    @Override
    public void afterPropertiesSet() {
        List<ModelPort> ports = modelPorts.orderedStream().toList();
        if (ports.isEmpty()) {
            throw new IllegalStateException(
                    "启用 AI 能力（basic-framework.ai.enabled=true）但未装配任何 ModelPort 实现；" + "请引入提供方实现或关闭该开关");
        }
        if (ports.size() > 1) {
            String names = ports.stream()
                    .map(port -> port.getClass().getName())
                    .sorted()
                    .collect(Collectors.joining("、"));
            throw new IllegalStateException("启用 AI 能力时要求唯一 ModelPort 实现，当前装配 " + ports.size() + " 个：" + names);
        }
        ModelPort port = ports.get(0);
        Set<ModelCapability> declared = port.capabilities();
        if (declared == null || declared.isEmpty()) {
            throw new IllegalStateException("ModelPort 实现 " + port.getClass().getName() + " 未声明任何模型能力");
        }
        Set<ModelCapability> required = EnumSet.copyOf(properties.getCapabilities());
        required.removeAll(declared);
        if (!required.isEmpty()) {
            String missing = required.stream().map(Enum::name).sorted().collect(Collectors.joining("、"));
            throw new IllegalStateException("ModelPort 实现 " + port.getClass().getName() + " 缺少已声明的模型能力：" + missing);
        }
    }
}

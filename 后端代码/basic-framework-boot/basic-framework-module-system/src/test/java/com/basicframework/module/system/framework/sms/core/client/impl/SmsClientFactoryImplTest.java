package com.basicframework.module.system.framework.sms.core.client.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.module.system.framework.sms.core.client.SmsClient;
import com.basicframework.module.system.framework.sms.core.enums.SmsChannelEnum;
import com.basicframework.module.system.framework.sms.core.property.SmsChannelProperties;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class SmsClientFactoryImplTest {

    private final SmsClientFactoryImpl factory = new SmsClientFactoryImpl();

    @Test
    void createOrUpdateSmsClient_reusesTheSameProviderClientForAConfigRefresh() {
        SmsClient first = factory.createOrUpdateSmsClient(properties(10L, SmsChannelEnum.ALIYUN, "initial-signature"));
        SmsClient refreshed = factory.createOrUpdateSmsClient(properties(10L, SmsChannelEnum.ALIYUN, "new-signature"));

        assertThat(refreshed).isSameAs(first).isInstanceOf(AliyunSmsClient.class);
        assertThat(((AbstractSmsClient) refreshed).properties.getSignature()).isEqualTo("new-signature");
    }

    @Test
    void createOrUpdateSmsClient_refreshesTheCachedClientInPlaceWhenSignatureChanges() {
        SmsClient aliyunClient =
                factory.createOrUpdateSmsClient(properties(10L, SmsChannelEnum.ALIYUN, "aliyun-signature"));

        SmsClient refreshedClient =
                factory.createOrUpdateSmsClient(properties(10L, SmsChannelEnum.ALIYUN, "refreshed-signature"));

        assertThat(aliyunClient).isInstanceOf(AliyunSmsClient.class);
        // 同渠道签名变更：原地 refresh，缓存实例保持不变
        assertThat(refreshedClient).isSameAs(aliyunClient);
        assertThat(((AbstractSmsClient) refreshedClient).properties.getSignature())
                .isEqualTo("refreshed-signature");
        assertThat(factory.getSmsClient(10L)).isSameAs(refreshedClient);
    }

    @Test
    void createTransientSmsClient_doesNotReplaceTheCachedClient() {
        SmsClient cached = factory.createOrUpdateSmsClient(properties(10L, SmsChannelEnum.ALIYUN, "cached-signature"));

        SmsClient transientClient =
                factory.createTransientSmsClient(properties(10L, SmsChannelEnum.ALIYUN, "transient-signature"));

        assertThat(transientClient).isInstanceOf(AliyunSmsClient.class);
        assertThat(factory.getSmsClient(10L)).isSameAs(cached);
    }

    @Test
    void getSmsClientByProviderCode_exposesTheDedicatedReceiptParser() {
        assertThat(factory.getSmsClient(SmsChannelEnum.ALIYUN.getCode())).isInstanceOf(AliyunSmsClient.class);
    }

    @Test
    void removeSmsClient_evictsTheCachedChannelClient() {
        factory.createOrUpdateSmsClient(properties(10L, SmsChannelEnum.ALIYUN, "cached-signature"));

        factory.removeSmsClient(10L);

        assertThat(factory.getSmsClient(10L)).isNull();
    }

    @Test
    void createOrUpdateSmsClient_failsLoudWhenNoClientImplementationMatchesTheChannel() throws Exception {
        // javac 将 enum switch 编译为合成 $SwitchMap 查找。枚举当前仅有 ALIYUN 一个值，
        // 正常调用不可能落空 switch；临时把唯一 case 的映射改成无匹配值以触发
        // switch 之后的 fail-closed 防御分支，并在结束时恢复映射保证其他用例不受影响。
        int[] switchMap = switchMapOfFactory();
        int originalMapping = switchMap[0];
        switchMap[0] = originalMapping + 1;
        try {
            assertThatThrownBy(() -> factory.createOrUpdateSmsClient(
                            properties(10L, SmsChannelEnum.ALIYUN, "unknown-signature")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("找不到合适的客户端实现")
                    .hasMessageContaining("id(10) code(ALIYUN)");
        } finally {
            switchMap[0] = originalMapping;
        }

        assertThat(factory.createOrUpdateSmsClient(properties(11L, SmsChannelEnum.ALIYUN, "normal-signature")))
                .isInstanceOf(AliyunSmsClient.class);
    }

    @Test
    void summarizeProperties_rendersNullAndPopulatedConfigurations() throws Exception {
        // null 配置分支在渠道解析之前不可达，直接调用私有方法锁定其摘要输出。
        Method summarize =
                SmsClientFactoryImpl.class.getDeclaredMethod("summarizeProperties", SmsChannelProperties.class);
        summarize.setAccessible(true);

        assertThat(summarize.invoke(factory, (SmsChannelProperties) null)).isEqualTo("null");
        assertThat(summarize.invoke(factory, properties(9L, SmsChannelEnum.ALIYUN, "summary-signature")))
                .isEqualTo("id(9) code(ALIYUN)");
    }

    private static int[] switchMapOfFactory() throws Exception {
        // 合成 switch-map 类不出现在 getDeclaredClasses() 中，按 javac 的编号约定逐个探测。
        String baseName = SmsClientFactoryImpl.class.getName();
        for (int index = 1; index <= 9; index++) {
            Class<?> synthetic;
            try {
                synthetic = Class.forName(baseName + "$" + index);
            } catch (ClassNotFoundException missingSyntheticClass) {
                break;
            }
            for (Field field : synthetic.getDeclaredFields()) {
                if (field.getName().startsWith("$SwitchMap$") && field.getType() == int[].class) {
                    field.setAccessible(true);
                    return (int[]) field.get(null);
                }
            }
        }
        throw new IllegalStateException("未找到 enum switch 的合成映射表");
    }

    private static SmsChannelProperties properties(Long id, SmsChannelEnum channel, String signature) {
        return new SmsChannelProperties()
                .setId(id)
                .setCode(channel.getCode())
                .setSignature(signature)
                .setApiKey("access-key")
                .setApiSecret("api-secret");
    }
}

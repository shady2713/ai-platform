package com.basicframework.module.ai.service.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.usage.AiQuotaLeaseDO;
import com.basicframework.module.ai.dal.mysql.usage.AiQuotaLeaseMapper;
import com.basicframework.module.ai.service.quota.dto.AiQuotaAcquireDTO;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/** Q02 配额占位：上限、幂等重入、租约到期回收、续租与释放。 */
class AiQuotaServiceTest {

    private final AiQuotaLeaseMapper mapper = mock(AiQuotaLeaseMapper.class);

    private final AiQuotaServiceImpl service = new AiQuotaServiceImpl(mapper);

    private static AiQuotaAcquireDTO request(int limit) {
        return new AiQuotaAcquireDTO()
                .setApplicationId(1L)
                .setInvocationId("inv_1")
                .setLease(Duration.ofMinutes(5))
                .setLimit(limit);
    }

    private static AiQuotaLeaseDO lease(String state, LocalDateTime until, int version) {
        return new AiQuotaLeaseDO()
                .setId(7L)
                .setLeaseKey("1:0:inv_1")
                .setApplicationId(1L)
                .setInvocationId("inv_1")
                .setState(state)
                .setLeaseUntil(until)
                .setVersion(version);
    }

    @Test
    void acquiresWhenUnderLimitAndRejectsWithExpiredLeasesNotCounted() {
        when(mapper.selectByLeaseKey(any())).thenReturn(null);
        when(mapper.countActive(anyLong(), any())).thenReturn(1L);
        when(mapper.insert(any(AiQuotaLeaseDO.class))).thenReturn(1);
        assertThat(service.acquire(request(2))).isTrue();

        // 已达上限：不排队不阻塞，返回未获得（调用方回 429 并说明原因）
        when(mapper.countActive(anyLong(), any())).thenReturn(2L);
        assertThat(service.acquire(request(2))).isFalse();
    }

    @Test
    void expiredLeasesAreReusableAndConcurrentInsertLosesGracefully() {
        // 已到期的活动占位：查询口径下不计入并发（activeCount 由 SQL 的 lease_until > now 保证），
        // 申请时按"重新占用"处理
        when(mapper.selectByLeaseKey(any()))
                .thenReturn(lease("ACTIVE", LocalDateTime.now().minusMinutes(1), 3));
        when(mapper.countActive(anyLong(), any())).thenReturn(0L);
        when(mapper.insert(any(AiQuotaLeaseDO.class))).thenReturn(1);
        assertThat(service.acquire(request(1))).isTrue();

        // 并发下唯一键判负：这次没拿到，由调用方重试（不重复占位）
        when(mapper.selectByLeaseKey(any())).thenReturn(null);
        when(mapper.insert(any(AiQuotaLeaseDO.class))).thenThrow(new DuplicateKeyException("uk_ai_quota_lease_key"));
        assertThat(service.acquire(request(1))).isFalse();
    }

    @Test
    void repeatedAcquireRenewsInsteadOfOccupyingAnotherSlot() {
        when(mapper.selectByLeaseKey(any()))
                .thenReturn(lease("ACTIVE", LocalDateTime.now().plusMinutes(1), 5));
        when(mapper.updateWithVersion(any(AiQuotaLeaseDO.class), anyInt())).thenReturn(1);

        assertThat(service.acquire(request(1))).isTrue();
        // 同一调用重复申请：走续租，不再 insert
        verify(mapper, never()).insert(any(AiQuotaLeaseDO.class));
    }

    @Test
    void renewFailsWhenLeaseWasReleasedOrReclaimed() {
        when(mapper.selectByLeaseKey(any()))
                .thenReturn(lease("RELEASED", LocalDateTime.now().plusMinutes(1), 1));
        assertThat(service.renew("1:0:inv_1", Duration.ofMinutes(1))).isFalse();

        when(mapper.selectByLeaseKey(any()))
                .thenReturn(lease("ACTIVE", LocalDateTime.now().minusSeconds(1), 1));
        assertThat(service.renew("1:0:inv_1", Duration.ofMinutes(1))).isFalse();

        when(mapper.selectByLeaseKey(any()))
                .thenReturn(lease("ACTIVE", LocalDateTime.now().plusSeconds(30), 2));
        when(mapper.updateWithVersion(any(AiQuotaLeaseDO.class), anyInt())).thenReturn(1);
        assertThat(service.renew("1:0:inv_1", Duration.ofMinutes(1))).isTrue();

        assertThatThrownBy(() -> service.renew("", Duration.ofMinutes(1))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.renew("1:0:inv_1", Duration.ZERO)).isInstanceOf(ServiceException.class);
    }

    @Test
    void releaseIsIdempotentAndActiveCountRequiresApplication() {
        when(mapper.selectByLeaseKey(any()))
                .thenReturn(lease("ACTIVE", LocalDateTime.now().plusMinutes(1), 1));
        when(mapper.updateWithVersion(any(AiQuotaLeaseDO.class), anyInt())).thenReturn(1);
        service.release("1:0:inv_1");
        verify(mapper).updateWithVersion(any(AiQuotaLeaseDO.class), anyInt());

        // 已释放：不重复写
        when(mapper.selectByLeaseKey(any()))
                .thenReturn(lease("RELEASED", LocalDateTime.now().plusMinutes(1), 2));
        service.release("1:0:inv_1");
        // 不存在的占位：静默返回（幂等）
        when(mapper.selectByLeaseKey(any())).thenReturn(null);
        service.release("1:0:missing");
        service.release(null);

        when(mapper.countActive(anyLong(), any())).thenReturn(3L);
        assertThat(service.activeCount(1L)).isEqualTo(3L);
        assertThatThrownBy(() -> service.activeCount(null)).isInstanceOf(ServiceException.class);
    }

    @Test
    void validatesAcquireInputAndLeaseBounds() {
        assertThatThrownBy(() -> service.acquire(null)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.acquire(request(0))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.acquire(request(1).setInvocationId(null)))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.acquire(request(1).setLease(Duration.ofHours(3))))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.acquire(request(1).setLease(null))).isInstanceOf(ServiceException.class);
    }

    @Test
    void leaseKeyIsStableAndScoped() {
        assertThat(AiQuotaServiceImpl.leaseKeyOf(request(1))).isEqualTo("1:0:inv_1");
        assertThat(AiQuotaServiceImpl.leaseKeyOf(request(1).setServiceId(9L))).isEqualTo("1:9:inv_1");
    }
}

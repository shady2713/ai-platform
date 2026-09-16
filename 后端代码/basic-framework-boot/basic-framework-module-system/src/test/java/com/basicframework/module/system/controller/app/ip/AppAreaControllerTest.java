package com.basicframework.module.system.controller.app.ip;

import static com.basicframework.module.system.enums.ErrorCodeConstants.AREA_CHINA_NOT_EXISTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.ip.core.Area;
import com.basicframework.framework.ip.core.utils.AreaUtils;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * {@link AppAreaController} 单元测试
 *
 */
class AppAreaControllerTest {

    private final AppAreaController controller = new AppAreaController();

    @Test
    void getAreaTree_publishesChildrenWithoutTheChinaWrapper() {
        Area city = new Area(310000, "上海", 2, null, new ArrayList<>());
        Area china = new Area(Area.ID_CHINA, "中国", 1, null, List.of(city));

        try (MockedStatic<AreaUtils> areaUtils = mockStatic(AreaUtils.class)) {
            areaUtils.when(() -> AreaUtils.getArea(Area.ID_CHINA)).thenReturn(china);

            assertThat(controller.getAreaTree().getData())
                    .singleElement()
                    .extracting("id", "name")
                    .containsExactly(310000, "上海");
        }
    }

    @Test
    void getAreaTree_failsWithPublishedErrorWhenChinaDataIsMissing() {
        try (MockedStatic<AreaUtils> areaUtils = mockStatic(AreaUtils.class)) {
            areaUtils.when(() -> AreaUtils.getArea(Area.ID_CHINA)).thenReturn(null);

            assertThatThrownBy(controller::getAreaTree)
                    .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode())
                            .isEqualTo(AREA_CHINA_NOT_EXISTS.getCode()));
        }
    }
}

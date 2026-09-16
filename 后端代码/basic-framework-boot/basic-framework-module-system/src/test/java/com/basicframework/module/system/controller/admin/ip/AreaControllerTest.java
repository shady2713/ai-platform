package com.basicframework.module.system.controller.admin.ip;

import static com.basicframework.module.system.enums.ErrorCodeConstants.AREA_CHINA_NOT_EXISTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.ip.core.Area;
import com.basicframework.framework.ip.core.utils.AreaUtils;
import com.basicframework.framework.ip.core.utils.IPUtils;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class AreaControllerTest {

    private final AreaController controller = new AreaController();

    @Test
    void treePublishesChildrenWithoutTheChinaWrapper() {
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
    void treeFailsWithThePublishedErrorWhenChinaDataIsMissing() {
        try (MockedStatic<AreaUtils> areaUtils = mockStatic(AreaUtils.class)) {
            areaUtils.when(() -> AreaUtils.getArea(Area.ID_CHINA)).thenReturn(null);

            assertThatThrownBy(controller::getAreaTree)
                    .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode())
                            .isEqualTo(AREA_CHINA_NOT_EXISTS.getCode()));
        }
    }

    @Test
    void ipLookupReturnsUnknownWhenTheDatabaseHasNoMatchingArea() {
        try (MockedStatic<IPUtils> ipUtils = mockStatic(IPUtils.class)) {
            ipUtils.when(() -> IPUtils.getArea("203.0.113.1")).thenReturn(null);

            assertThat(controller.getAreaByIp("203.0.113.1").getData()).isEqualTo("未知");
        }
    }

    @Test
    void ipLookupFormatsTheResolvedArea() {
        Area city = new Area(310000, "上海", 2, null, new ArrayList<>());
        try (MockedStatic<IPUtils> ipUtils = mockStatic(IPUtils.class);
                MockedStatic<AreaUtils> areaUtils = mockStatic(AreaUtils.class)) {
            ipUtils.when(() -> IPUtils.getArea("198.51.100.7")).thenReturn(city);
            areaUtils.when(() -> AreaUtils.format(310000)).thenReturn("上海");

            assertThat(controller.getAreaByIp("198.51.100.7").getData()).isEqualTo("上海");
        }
    }
}

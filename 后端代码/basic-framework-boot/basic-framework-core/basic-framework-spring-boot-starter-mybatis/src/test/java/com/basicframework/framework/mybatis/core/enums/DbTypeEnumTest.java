package com.basicframework.framework.mybatis.core.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.annotation.DbType;
import org.junit.jupiter.api.Test;

class DbTypeEnumTest {

    @Test
    void find_mapsKnownProductsAndFallsBackForUnknownProducts() {
        assertThat(DbTypeEnum.find("MySQL")).isEqualTo(DbType.MYSQL);
        assertThat(DbTypeEnum.find("PostgreSQL")).isEqualTo(DbType.POSTGRE_SQL);
        assertThat(DbTypeEnum.find("unknown-database")).isEqualTo(DbType.OTHER);
        assertThat(DbTypeEnum.find(" ")).isNull();
    }

    @Test
    void getFindInSetTemplate_rejectsUnsupportedDatabaseTypes() {
        assertThat(DbTypeEnum.getFindInSetTemplate(DbType.MYSQL)).contains("FIND_IN_SET");
        assertThatThrownBy(() -> DbTypeEnum.getFindInSetTemplate(DbType.H2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("H2");
        assertThatThrownBy(() -> DbTypeEnum.getFindInSetTemplate(DbType.OTHER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OTHER");
    }
}

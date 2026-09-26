package com.travelmind.aiagent.model.entity;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class UserTableMappingTest {

    @Test
    void mapsLegacyCamelCaseColumnsExplicitly() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "UserTableMappingTest");
        TableInfo tableInfo = TableInfoHelper.initTableInfo(assistant, User.class);
        assertThat(tableInfo.getTableName()).isEqualTo("user");

        Map<String, String> columns = tableInfo.getFieldList().stream()
                .collect(Collectors.toMap(TableFieldInfo::getProperty, TableFieldInfo::getColumn));

        assertThat(columns)
                .containsEntry("userAccount", "userAccount")
                .containsEntry("userPassword", "userPassword")
                .containsEntry("userRole", "userRole")
                .containsEntry("isDelete", "isDelete");
    }
}

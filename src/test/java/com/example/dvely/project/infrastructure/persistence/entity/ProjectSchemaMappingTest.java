package com.example.dvely.project.infrastructure.persistence.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.chat.infrastructure.persistence.entity.ConversationEntity;
import com.example.dvely.domainbinding.infrastructure.persistence.entity.DomainBindingEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class ProjectSchemaMappingTest {

    @Test
    void projectEntitiesUseProjectTableAndIdColumns() throws NoSuchFieldException {
        assertThat(ProjectEntity.class.getAnnotation(Table.class).name()).isEqualTo("projects");
        assertColumnName(ProjectEntity.class, "id", "project_id");
        assertColumnName(ConversationEntity.class, "projectId", "project_id");
        assertColumnName(DomainBindingEntity.class, "projectId", "project_id");
    }

    private void assertColumnName(Class<?> entityType,
                                  String fieldName,
                                  String expectedColumnName) throws NoSuchFieldException {
        Field field = entityType.getDeclaredField(fieldName);

        assertThat(field.getAnnotation(Column.class).name()).isEqualTo(expectedColumnName);
    }
}

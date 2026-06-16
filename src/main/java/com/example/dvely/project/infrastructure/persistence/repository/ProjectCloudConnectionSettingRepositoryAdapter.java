package com.example.dvely.project.infrastructure.persistence.repository;

import com.example.dvely.project.domain.model.ProjectCloudConnectionSetting;
import com.example.dvely.project.domain.repository.ProjectCloudConnectionSettingRepository;
import com.example.dvely.project.infrastructure.persistence.entity.ProjectCloudConnectionSettingEntity;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ProjectCloudConnectionSettingRepositoryAdapter
        implements ProjectCloudConnectionSettingRepository {

    private final SpringDataProjectCloudConnectionSettingRepository springDataRepository;

    @Override
    public ProjectCloudConnectionSetting save(ProjectCloudConnectionSetting setting) {
        ProjectCloudConnectionSettingEntity entity = springDataRepository.findById(setting.getProjectId())
                .orElseGet(() -> ProjectCloudConnectionSettingEntity.from(setting));
        entity.updateFrom(setting);
        return springDataRepository.save(entity).toDomain();
    }

    @Override
    public Optional<ProjectCloudConnectionSetting> findByProjectId(Long projectId) {
        return springDataRepository.findById(projectId).map(ProjectCloudConnectionSettingEntity::toDomain);
    }

    @Override
    public void deleteByProjectId(Long projectId) {
        springDataRepository.deleteById(projectId);
    }
}

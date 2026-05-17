package com.example.dvely.cloudconnection.application.facade;

import com.example.dvely.cloudconnection.application.command.CloudConnectionCommandService;
import com.example.dvely.cloudconnection.application.command.dto.CreateCloudConnectionCommand;
import com.example.dvely.cloudconnection.application.query.CloudConnectionQueryService;
import com.example.dvely.cloudconnection.application.result.CloudConnectionHealthResult;
import com.example.dvely.cloudconnection.application.result.CloudConnectionResult;
import com.example.dvely.cloudconnection.application.result.CreateCloudConnectionResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CloudConnectionFacade {

    private final CloudConnectionCommandService cloudConnectionCommandService;
    private final CloudConnectionQueryService cloudConnectionQueryService;

    public List<CloudConnectionResult> getCloudConnections(Long ownerUserId) {
        return cloudConnectionQueryService.getCloudConnections(ownerUserId);
    }

    public CreateCloudConnectionResult create(Long ownerUserId, CreateCloudConnectionCommand command) {
        return cloudConnectionCommandService.create(ownerUserId, command);
    }

    public CloudConnectionResult getCloudConnection(Long ownerUserId, Long cloudConnectionId) {
        return cloudConnectionQueryService.getCloudConnection(ownerUserId, cloudConnectionId);
    }

    public CloudConnectionHealthResult checkHealth(Long ownerUserId, Long cloudConnectionId) {
        return cloudConnectionCommandService.checkHealth(ownerUserId, cloudConnectionId);
    }

    public void delete(Long ownerUserId, Long cloudConnectionId) {
        cloudConnectionCommandService.delete(ownerUserId, cloudConnectionId);
    }
}

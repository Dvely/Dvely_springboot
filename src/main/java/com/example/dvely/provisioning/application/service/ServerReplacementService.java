package com.example.dvely.provisioning.application.service;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.provisioning.domain.model.ProvisionedServer;
import com.example.dvely.provisioning.domain.repository.ProvisionedServerRepository;
import com.example.dvely.provisioning.domain.value.ServerStatus;
import com.example.dvely.provisioning.infrastructure.Ec2Provisioner;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * EC2 재배포 블루그린 cutover. 재배포로 뜬 새 서버가 RUNNING 되면(supersedesServerId 로 교체 대상을
 * 기억) 이 워커가 <b>옛 EIP 를 새 인스턴스로 옮기고 옛 서버를 종료</b>한다. dnsTarget(IP)이 그대로라
 * 도메인·인증서를 안 건드리고 트래픽만 새 인스턴스로 넘어가며, 옛 서버가 안 남아 고아 과금이 없다.
 *
 * <p>순서: ①옛 EIP 를 새 인스턴스로 reassociate → ②새 서버가 자기 launch 때 받은 EIP 는 유휴가 되므로
 * release → ③옛 서버에서 EIP·publicHost 를 분리(그래야 종료 정리가 그 EIP·도메인을 안 건드린다) →
 * ④옛 인스턴스만 종료(SSM·S3 이미지 등 프로젝트 공유 자원은 새 서버가 쓰므로 정리하지 않음).</p>
 *
 * <p>RUNNING + supersedes 남은 행을 폴링해 멱등하게 진행한다 — 도중 실패해도 다음 주기에 이어서 마무리
 * (detach·terminate 는 멱등, EIP 이미 옮겼으면 그 블록은 건너뜀). 완료되면 supersedes 를 지워 재진입을
 * 막는다. 연결이 사라져 자격을 못 얻으면 그 주기엔 건너뛴다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServerReplacementService {

    private static final int BATCH = 20;

    private final ProvisionedServerRepository serverRepository;
    private final CloudConnectionRepository cloudConnectionRepository;
    private final Ec2Provisioner ec2;

    @Scheduled(fixedDelayString = "${qeploy.provisioning.replace-poll-interval-ms:20000}")
    public void completePendingReplacements() {
        for (ProvisionedServer newServer : serverRepository.findRunningWithPendingReplacement(BATCH)) {
            try {
                completeReplacement(newServer);
            } catch (RuntimeException e) {
                // 이 행만 건너뛰고 다음 주기에 다시 본다 — 옛 서버가 잠시 더 남을 수 있으나(과금) 재시도로 마무리.
                log.warn("재배포 교체 실패(다음 주기 재시도, 옛 서버 잔존 가능): newServerId={} supersedes={} 원인={}",
                        newServer.getId(), newServer.getSupersedesServerId(), e.toString());
            }
        }
    }

    private void completeReplacement(ProvisionedServer newServer) {
        Long oldServerId = newServer.getSupersedesServerId();
        if (oldServerId == null) {
            return;
        }
        ProvisionedServer oldServer = serverRepository.findById(oldServerId).orElse(null);
        if (oldServer == null || oldServer.getStatus() == ServerStatus.TERMINATED) {
            // 교체 대상이 이미 없다(먼저 종료됐거나 사라짐) — 표시만 지운다.
            newServer.clearSupersedes();
            serverRepository.save(newServer);
            return;
        }
        Optional<CloudConnection> connection = newServer.getCloudConnectionId() == null
                ? Optional.empty()
                : cloudConnectionRepository.findById(newServer.getCloudConnectionId());
        if (connection.isEmpty()) {
            log.warn("재배포 교체 건너뜀(클라우드 연결 없음): newServerId={} cloudConnectionId={}",
                    newServer.getId(), newServer.getCloudConnectionId());
            return;
        }
        CloudConnection conn = connection.get();

        // ① 옛 EIP 를 새 인스턴스로 이동(있을 때만). 이미 옮겼으면 옛 서버 EIP 가 비어 있어 이 블록을 건너뛴다.
        if (oldServer.getElasticIpAllocationId() != null && newServer.getInstanceId() != null) {
            String oldAllocation = oldServer.getElasticIpAllocationId();
            String oldIp = oldServer.getPublicHost();
            String newAllocation = newServer.getElasticIpAllocationId();

            ec2.reassociateElasticIp(conn, oldAllocation, newServer.getInstanceId());
            // ② 새 서버가 launch 때 받은 자기 EIP 는 이제 유휴 → release(다르면). 실패해도 고아 EIP 스위퍼가 회수.
            if (newAllocation != null && !newAllocation.equals(oldAllocation)) {
                try {
                    ec2.releaseElasticIp(conn, newAllocation);
                } catch (RuntimeException e) {
                    log.warn("교체 중 새 서버의 유휴 EIP release 실패(스위퍼가 회수): allocationId={} 원인={}",
                            newAllocation, e.toString());
                }
            }
            // ③ 새 서버가 옛 EIP·주소를 넘겨받고, 옛 서버는 EIP·주소를 분리(종료 정리가 EIP·도메인을 안 건드리게).
            newServer.reassignElasticIp(oldAllocation, oldIp);
            oldServer.detachElasticIp();
            serverRepository.save(newServer);
            serverRepository.save(oldServer);
        }

        // ④ 옛 인스턴스만 종료한다 — SSM·S3 이미지 등 프로젝트 공유 자원은 새 서버가 쓰므로 정리하지 않는다.
        if (oldServer.getInstanceId() != null) {
            ec2.terminate(conn, oldServer.getInstanceId());
        }
        oldServer.markTerminated();
        serverRepository.save(oldServer);

        newServer.clearSupersedes();
        serverRepository.save(newServer);
        log.info("재배포 교체 완료(블루그린): newServerId={} host={} 옛 serverId={} 종료",
                newServer.getId(), newServer.getPublicHost(), oldServerId);
    }
}

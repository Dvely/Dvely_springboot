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

    // 다중 인스턴스 리스 소유자 식별자(JVM 별 유일). Agent 워커와 동형.
    private final String workerId = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();

    @Scheduled(fixedDelayString = "${qeploy.provisioning.replace-poll-interval-ms:20000}")
    public void completePendingReplacements() {
        for (ProvisionedServer newServer : serverRepository.findRunningWithPendingReplacement(BATCH)) {
            // 다중 인스턴스에서 두 곳이 같은 서버의 EIP 재연결·종료를 동시에 하지 않게 리스로 claim 한다.
            // 못 잡으면(다른 인스턴스가 쥠) 이 틱엔 건너뛴다 — 그쪽이 마무리하거나, 죽으면 리스 만료 후 이어받는다.
            if (!serverRepository.claimForReplacement(newServer.getId(), workerId)) {
                continue;
            }
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
        if (oldServer == null) {
            newServer.clearSupersedes();   // 교체 대상이 사라짐 — 표시만 지운다.
            serverRepository.save(newServer);
            return;
        }
        // 교체 대상이 종착(FAILED·TERMINATED)이면 체인 링크가 끊긴 것이다. 그 서버의 교체 대상(더 앞선 라이브
        // 서버)으로 승계해 그쪽을 대신 교체한다 — 더블 재배포에서 중간 서버가 실패해도 최신 서버가 진짜 라이브
        // 서버를 이어받게. 승계할 대상이 없으면(체인 전체가 종착) 표시만 지운다. supersedes 는 늘 더 오래된
        // 서버를 가리켜 순환이 없으므로 이 승계는 유한하게 끝난다.
        if (oldServer.getStatus().isTerminal()) {
            if (oldServer.hasSupersedes()) {
                newServer.assignSupersedes(oldServer.getSupersedesServerId());
            } else {
                newServer.clearSupersedes();
            }
            serverRepository.save(newServer);
            return;
        }
        // 교체 대상이 아직 뜨는 중이면(PENDING/QUEUED/BUILDING/PROVISIONING) 기다린다 — 그 서버가 먼저
        // RUNNING 이 되어 라이브 EIP 를 넘겨받아야 이 새 서버가 그걸 다시 이어받는다(A→B→C 순서 보장).
        if (oldServer.getStatus() != ServerStatus.RUNNING) {
            return;   // 다음 주기에 다시 본다
        }
        // 교체 대상이 RUNNING 이라도 '자기 교체를 아직 안 끝냈으면'(supersedes 남음) 기다린다 — 그 서버가 먼저
        // 정착해 라이브 EIP 를 쥐어야 넘겨받을 게 생긴다. 이 가드로 같은 주기 내 처리 순서와 무관하게 안전하다
        // (C 를 B 보다 먼저 처리해도, C 는 B 가 정착할 때까지 기다린다).
        if (oldServer.hasSupersedes()) {
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

package com.example.dvely.provisioning.infrastructure.worker;

import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.provisioning.domain.repository.ProvisionedServerRepository;
import com.example.dvely.provisioning.domain.value.ServerStatus;
import com.example.dvely.provisioning.infrastructure.Ec2Provisioner;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 고아 Elastic IP 자동 회수. 정상 실패 경로의 누수는 {@link Ec2Provisioner#allocateAndAssociateElasticIp}
 * 가 즉시 release 로 막지만, 잔여(종료 시 release 실패, allocate~associate 사이 프로세스 크래시)까지는
 * 코드 가드로 못 막는다. 이 워커가 자기치유한다 — 안 그러면 유휴 EIP 가 사용자 화면 어디에도 안 뜬 채
 * (비용은 DB 행 기준 추정치라) 조용히 과금되고, 사용자가 AWS 콘솔에서 손으로 지워야 한다.
 *
 * <p>회수 대상: {@code managed-by=qeploy} 태그가 붙었고 <b>미연결</b>이며, RUNNING 서버가 소유하지
 * 않은 EIP. 인플라이트 배포(QUEUED/BUILDING/PROVISIONING)가 있는 연결은 <b>통째로 건너뛴다</b> —
 * 그 사이 방금 할당됐지만 아직 연결 전인 EIP 를 오회수해 배포를 깨뜨리지 않기 위함(재정렬 대신 코스한
 * 안전장치). 건너뛴 연결의 고아는 배포가 끝난 다음 주기에 회수된다(백그라운드라 지연 회수로 충분).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrphanElasticIpSweeper {

    private final ProvisionedServerRepository serverRepository;
    private final CloudConnectionRepository cloudConnectionRepository;
    private final Ec2Provisioner ec2;

    @Scheduled(fixedDelayString = "${qeploy.provisioning.eip-sweep-interval-ms:600000}")
    public void sweep() {
        for (Long connectionId : serverRepository.findDistinctCloudConnectionIds()) {
            if (serverRepository.existsInFlightByCloudConnectionId(connectionId)) {
                continue;   // 배포 진행 중 — 이 연결은 이번 주기 건너뛴다(오회수 방지)
            }
            cloudConnectionRepository.findById(connectionId).ifPresent(connection -> {
                Set<String> ownedByRunning = Set.copyOf(
                        serverRepository.findElasticIpAllocationIds(connectionId, ServerStatus.RUNNING));
                try {
                    List<Ec2Provisioner.QeployEip> eips = ec2.listQeployElasticIps(connection);
                    for (Ec2Provisioner.QeployEip eip : eips) {
                        if (eip.associated() || ownedByRunning.contains(eip.allocationId())) {
                            continue;   // 살아있는 자원 — 두 겹으로 지킨다(연결됨 or RUNNING 소유)
                        }
                        ec2.releaseElasticIp(connection, eip.allocationId());
                        log.warn("고아 EIP 자동 회수: allocationId={} publicIp={} connectionId={}",
                                eip.allocationId(), eip.publicIp(), connectionId);
                    }
                } catch (RuntimeException e) {
                    // 한 연결이 실패해도(권한·일시 오류) 다음 연결은 계속 훑는다.
                    log.warn("EIP 고아 청소 실패(connectionId={}): {}", connectionId, e.toString());
                }
            });
        }
    }
}

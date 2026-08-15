package com.example.dvely.approval.application.query;

import com.example.dvely.approval.application.result.ApprovalInput;
import com.example.dvely.approval.application.result.ApprovalResult;
import com.example.dvely.approval.domain.value.ApprovalStatus;
import com.example.dvely.approval.domain.value.ApprovalType;
import com.example.dvely.project.domain.value.RepositoryNamePolicy;
import com.example.dvely.approval.domain.model.Approval;
import com.example.dvely.approval.domain.repository.ApprovalRepository;
import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.project.domain.exception.ProjectNotFoundException;
import com.example.dvely.project.domain.repository.ProjectRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ApprovalQueryService {

    private final ApprovalRepository approvalRepository;
    private final ProjectRepository projectRepository;

    public List<ApprovalResult> getProjectApprovals(Long ownerUserId, Long projectId) {
        projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, ownerUserId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId, ownerUserId));
        return approvalRepository
                .findByProjectIdAndOwnerUserIdOrderByCreatedAtDesc(projectId, ownerUserId)
                .stream()
                .map(this::toResult)
                .toList();
    }

    public ApprovalResult getApproval(Long ownerUserId, Long approvalId) {
        return toResult(findOwned(ownerUserId, approvalId));
    }

    private Approval findOwned(Long ownerUserId, Long approvalId) {
        return approvalRepository.findByIdAndOwnerUserId(approvalId, ownerUserId)
                .orElseThrow(() -> new NotFoundException("승인을 찾을 수 없습니다. approvalId=" + approvalId));
    }

    /**
     * REPOSITORY_BINDING 은 승인할 때 저장소 이름을 함께 받는다. 그 기본값을 summary
     * ("[저장소 연결] 6-20")에서 접두사를 잘라 쓰게 두면 FE 가 표시용 문구에 의존하게 되므로
     * 별도 필드로 내려준다. 표시 문구는 서버가 언제든 바꿀 수 있는 값이다.
     *
     * 값은 게이트가 쓴 것과 같은 RepositoryNamePolicy 로 다시 계산한다. 저장할 필요가 없고 두
     * 경로가 어긋날 일도 없다.
     *
     * 이미 결정된 승인에는 붙이지 않는다. 입력창을 그릴 이유가 없고, 목록 조회가 승인 건마다
     * 프로젝트를 더 읽는 것도 막는다.
     */
    private ApprovalInput inputFor(Approval approval) {
        if (approval.getType() != ApprovalType.REPOSITORY_BINDING
                || approval.getStatus() != ApprovalStatus.PENDING) {
            return null;
        }
        return projectRepository
                .findByIdAndOwnerUserIdAndDeletedFalse(approval.getProjectId(), approval.getOwnerUserId())
                .map(project -> new ApprovalInput(
                        "repositoryName",
                        RepositoryNamePolicy.forProject(project.getName(), approval.getOwnerUserId()),
                        false,
                        "^[a-z0-9-]+$",
                        100
                ))
                .orElse(null);
    }

    public ApprovalResult toResult(Approval approval) {
        return new ApprovalResult(
                approval.getId(),
                approval.getProjectId(),
                approval.getConversationId(),
                approval.getTaskId(),
                approval.getType().name(),
                approval.getStatus().name(),
                approval.getSummary(),
                inputFor(approval),
                approval.getCreatedAt(),
                approval.getDecidedAt()
        );
    }
}

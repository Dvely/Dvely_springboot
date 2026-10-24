package com.example.dvely.cloudconnection.application.service;

import com.example.dvely.cloudconnection.application.result.CloudConnectionRequirementsResult;
import com.example.dvely.cloudconnection.application.result.CloudConnectionRequirementsResult.CredentialCase;
import com.example.dvely.cloudconnection.application.result.CloudConnectionRequirementsResult.CredentialOption;
import com.example.dvely.cloudconnection.application.result.CloudConnectionRequirementsResult.PolicyModule;
import com.example.dvely.cloudconnection.application.result.CloudConnectionRequirementsResult.RequirementField;
import com.example.dvely.cloudconnection.application.result.CloudConnectionRequirementsResult.SetupStep;
import com.example.dvely.cloudconnection.domain.value.AwsCredentialType;
import com.example.dvely.cloudconnection.domain.value.CloudProvider;
import com.example.dvely.cloudconnection.infrastructure.config.CloudConnectionGuideProperties;
import com.example.dvely.cloudconnection.infrastructure.external.AwsPolicyDocumentLoader;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 클라우드 연결 가이드를 조립한다. 추천 정책은 코드가 실제 호출하는 AWS 액션에서 도출한 리소스
 * 파일을 단일 소스로 서빙하고(하드코딩 금지 — BE 가 액션을 늘리면 정책도 같이 바뀌게), 필드·단계·주의는
 * 인증 방식별로 구성한다. 지금은 AWS 만 제공한다.
 *
 * <p>역할 위임(ROLE_ARN)은 컨트롤 플레인이 사용자 역할을 STS AssumeRole 할 <b>소스 신원</b>을 가져야
 * 동작한다({@code AwsCredentialsResolver} 의 DefaultCredentialsProvider). 그 신원의 ARN 이
 * {@code platform-principal-arn} 으로 설정돼 있지 않으면 위임이 실제로 성립하지 않으므로, 가이드는
 * ROLE_ARN 을 제시하지 않고 <b>실동작하는 ACCESS_KEY 만</b> 안내한다. ARN 이 설정되면 자동으로 다시 열린다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CloudConnectionRequirementsService {

    private static final String POLICY_NAME = "QeployDeployPolicy";
    private static final String ROLE_NAME = "QeployDeployRole";

    private final AwsPolicyDocumentLoader policyDocumentLoader;
    private final CloudConnectionGuideProperties guideProperties;

    @PostConstruct
    void warnIfRoleDelegationNotWired() {
        if (!guideProperties.hasPlatformPrincipalArn()) {
            log.warn("클라우드 연결 가이드: QEPLOY_CLOUD_CONNECTION_PLATFORM_PRINCIPAL_ARN 미설정 — 역할 위임"
                    + "(ROLE_ARN)은 AssumeRole 소스 신원이 없어 동작하지 않으므로 가이드는 액세스 키만 안내합니다. "
                    + "역할 위임을 켜려면 컨트롤 플레인에 sts:AssumeRole 신원을 부여하고 그 ARN 을 설정하세요.");
        }
    }

    public CloudConnectionRequirementsResult getRequirements(String provider, String credentialType) {
        CloudProvider cloudProvider = CloudProvider.from(provider);
        if (cloudProvider != CloudProvider.AWS) {
            throw new IllegalArgumentException("현재 AWS 연결 가이드만 제공합니다. provider=" + provider);
        }
        // 역할 위임은 컨트롤 플레인에 AssumeRole 소스 신원(platform-principal-arn)이 있어야 실제로 동작한다.
        // 없으면 ROLE_ARN 을 아예 제시하지 않고 실동작하는 ACCESS_KEY 만 안내한다 — 사용자를 깨진 흐름으로
        // 보내지 않기 위해서다. ARN 이 설정되면 이 분기는 통과되어 ROLE_ARN 이 다시 열린다.
        boolean roleArnAvailable = guideProperties.hasPlatformPrincipalArn();
        if (!roleArnAvailable) {
            return accessKeyGuide(false);
        }
        // 가이드는 방식 미지정이면 권장(ROLE_ARN)을 보여준다. AwsCredentialType.from 은 하위호환상 빈 값을
        // ACCESS_KEY 로 떨구므로 여기서 명시적으로 나눈다.
        AwsCredentialType type = (credentialType == null || credentialType.isBlank())
                ? AwsCredentialType.ROLE_ARN
                : AwsCredentialType.from(credentialType);
        return type == AwsCredentialType.ROLE_ARN ? roleArnGuide() : accessKeyGuide(true);
    }

    private CloudConnectionRequirementsResult roleArnGuide() {
        return new CloudConnectionRequirementsResult(
                CloudProvider.AWS.name(),
                AwsCredentialType.ROLE_ARN.name(),
                AwsCredentialType.ROLE_ARN.name(),
                credentialOptions(true),
                List.of(
                        field("displayName", "연결 이름", "이 연결을 화면에서 구분할 이름입니다.",
                                "자유롭게 입력", "AWS 서울 계정", true, false),
                        field("region", "리전", "리소스를 배포할 AWS 리전입니다.",
                                "리소스를 만들 리전을 선택 (서울=ap-northeast-2)", "ap-northeast-2", true, false),
                        field("accountId", "AWS 계정 ID", "12자리 계정 번호. 위임한 역할이 이 계정의 것인지 확인하는 데 씁니다.",
                                "AWS 콘솔 우측 상단 계정 메뉴 > 계정 ID", "123456789012", true, false),
                        field("roleArn", "역할 ARN", "아래 단계로 만든 역할의 ARN 입니다.",
                                "IAM > 역할 > 방금 만든 역할 상세 > ARN 복사",
                                "arn:aws:iam::123456789012:role/QeployDeployRole", true, false)
                ),
                List.of(
                        step(1, "권한 정책 생성",
                                "IAM 콘솔 > 정책 > 정책 생성 > JSON 탭에 아래 '추천 권한 정책'을 붙여넣고 "
                                        + "이름 '" + POLICY_NAME + "' 로 저장합니다."),
                        step(2, "역할 생성 + 신뢰 정책",
                                "IAM 콘솔 > 역할 > 역할 생성 > 신뢰할 수 있는 엔터티에서 '사용자 지정 신뢰 정책'을 "
                                        + "고르고 아래 '신뢰 정책'을 붙여넣습니다."),
                        step(3, "권한 정책 연결",
                                "다음 화면에서 1단계의 '" + POLICY_NAME + "' 을 연결하고 역할 이름을 '" + ROLE_NAME
                                        + "' 로 저장합니다."),
                        step(4, "역할 ARN 입력",
                                "만든 역할 상세에서 ARN 을 복사해 위 '역할 ARN' 칸에 붙여넣고, 리전과 계정 ID 를 "
                                        + "입력한 뒤 연결합니다.")
                ),
                POLICY_NAME,
                ROLE_NAME,
                policyDocumentLoader.loadDeployPolicy(),
                policyDocumentLoader.loadTrustPolicy(guideProperties.platformPrincipalArnOrPlaceholder()),
                notes(true, true),
                credentialCases(true),
                policyModules()
        );
    }

    private CloudConnectionRequirementsResult accessKeyGuide(boolean roleArnAvailable) {
        // 역할 위임이 준비된 환경에서만 권장을 ROLE_ARN 으로 둔다. 준비 안 됐으면 실동작하는 ACCESS_KEY 가 권장.
        String recommended = roleArnAvailable
                ? AwsCredentialType.ROLE_ARN.name()
                : AwsCredentialType.ACCESS_KEY.name();
        return new CloudConnectionRequirementsResult(
                CloudProvider.AWS.name(),
                AwsCredentialType.ACCESS_KEY.name(),
                recommended,
                credentialOptions(roleArnAvailable),
                List.of(
                        field("displayName", "연결 이름", "이 연결을 화면에서 구분할 이름입니다.",
                                "자유롭게 입력", "AWS 서울 계정", true, false),
                        field("region", "리전", "리소스를 배포할 AWS 리전입니다.",
                                "리소스를 만들 리전을 선택 (서울=ap-northeast-2)", "ap-northeast-2", true, false),
                        field("accountId", "AWS 계정 ID", "12자리 계정 번호. 키가 이 계정의 것인지 확인하는 데 씁니다.",
                                "AWS 콘솔 우측 상단 계정 메뉴 > 계정 ID", "123456789012", false, false),
                        field("accessKeyId", "액세스 키 ID", "아래 '추천 권한 정책'을 붙인 IAM 사용자의 액세스 키입니다.",
                                "IAM > 사용자 > 보안 자격 증명 > 액세스 키 만들기", "AKIA...", true, false),
                        field("secretAccessKey", "시크릿 액세스 키", "액세스 키를 만들 때 한 번만 보이는 비밀값입니다. AES 로 암호화 저장되며 응답에 노출되지 않습니다.",
                                "액세스 키 생성 시 함께 발급 (그때만 확인 가능)", "****", true, true)
                ),
                List.of(
                        step(1, "권한 정책 생성",
                                "IAM 콘솔 > 정책 > 정책 생성 > JSON 탭에 아래 '추천 권한 정책'을 붙여넣고 "
                                        + "이름 '" + POLICY_NAME + "' 로 저장합니다."),
                        step(2, "IAM 사용자에 정책 연결",
                                "그 정책을 연결할 IAM 사용자를 만들거나 고릅니다(프로그래밍 방식 액세스)."),
                        step(3, "액세스 키 발급",
                                "IAM > 사용자 > 보안 자격 증명 > 액세스 키 만들기 로 키를 발급합니다. "
                                        + "시크릿은 이때만 보이므로 바로 복사합니다."),
                        step(4, "키 입력",
                                "발급한 액세스 키 ID·시크릿과 리전을 입력한 뒤 연결합니다.")
                ),
                POLICY_NAME,
                ROLE_NAME,
                policyDocumentLoader.loadDeployPolicy(),
                null,
                notes(false, roleArnAvailable),
                credentialCases(roleArnAvailable),
                policyModules()
        );
    }

    /**
     * 방식 선택지. 역할 위임이 준비된 환경에서만 ROLE_ARN 을 (권장으로) 제시한다. 준비 안 됐으면 실동작하는
     * ACCESS_KEY 만 내보내 사용자가 안 되는 역할 위임을 고르지 못하게 한다(FE 는 선택지가 하나면 탭을 감춘다).
     */
    private List<CredentialOption> credentialOptions(boolean roleArnAvailable) {
        CredentialOption accessKey = new CredentialOption(AwsCredentialType.ACCESS_KEY.name(), "액세스 키",
                !roleArnAvailable,
                "설정이 더 간단하지만 장기 키를 암호화해 저장합니다. 임시 자격(ASIA)은 만료되어 받지 않습니다.");
        if (!roleArnAvailable) {
            return List.of(accessKey);
        }
        return List.of(
                new CredentialOption(AwsCredentialType.ROLE_ARN.name(), "역할 위임 (Role ARN)", true,
                        "장기 키를 Qeploy 에 저장하지 않습니다. 사용자가 만든 역할을 필요할 때만 위임받아 씁니다. 권장."),
                accessKey
        );
    }

    private List<String> notes(boolean roleArnGuide, boolean roleArnAvailable) {
        String platformNote = roleArnGuide
                // roleArnGuide 는 위임이 준비된 환경에서만 도달하므로 신뢰 정책 Principal 은 실제 값이다.
                ? "신뢰 정책의 Principal 은 이 역할을 위임받는 Qeploy 컨트롤 플레인의 identity 입니다 — 그대로 두세요."
                // ACCESS_KEY 가이드인데 역할 위임이 아직 준비 안 됐으면 그 사실을 알린다(왜 탭이 하나뿐인지).
                : (roleArnAvailable ? null
                    : "역할 위임(ROLE_ARN)은 이 환경에서 아직 준비되지 않아(컨트롤 플레인의 AssumeRole 소스 신원 "
                            + "미구성) 액세스 키로 안내합니다. 준비되면 역할 위임이 자동으로 열립니다.");
        return java.util.stream.Stream.of(
                "연결 검증은 자격 유효성(STS GetCallerIdentity)만 확인합니다. 위 권한 정책이 빠지면 연결은 "
                        + "성공해도 실제 배포에서 권한 부족으로 실패합니다 — 그래서 정책을 먼저 붙여야 합니다.",
                "권한 정책은 S3(qeploy-* 버킷)·SSM 파라미터(/qeploy/*)·IAM 인스턴스 역할(qeploy-instance-*)로 "
                        + "리소스를 좁혀 최소권한을 지향합니다.",
                "Docker 이미지 전송을 ECR 모드로 켠 경우에만 ECR 권한(ecr:CreateRepository, "
                        + "ecr:GetAuthorizationToken, ecr:DeleteRepository 및 이미지 push 액션)을 추가하세요. 기본 S3 "
                        + "모드는 필요 없습니다.",
                "ECR 모드라면 ecr:PutLifecyclePolicy 도 함께 넣어주세요. 배포는 항상 :latest 로 push 하므로 "
                        + "직전 이미지가 태그를 잃고 남습니다 — 이 권한이 없으면 만료 정책을 걸 수 없어 이미지가 "
                        + "계속 쌓이고 ECR 스토리지 비용이 시간에 비례해 늘어납니다. 없어도 배포는 성공하지만 "
                        + "정리가 안 됩니다.",
                platformNote
        ).filter(java.util.Objects::nonNull).toList();
    }

    /**
     * 자격 종류별 설명. 임시 자격은 받지 않지만 목록에서 빼지 않는다 — AWS Academy·SSO 로그인·
     * {@code aws sts} 가 주는 키가 바로 그것이라, 사용자는 자기 키가 왜 거절되는지와 대신 무엇을 쓰면
     * 되는지를 여기서 알아야 한다(#429 이전에 받은 임시 자격 연결이 실제로 만료돼 자원을 못 지웠다, #491).
     */
    private List<CredentialCase> credentialCases(boolean roleArnAvailable) {
        CredentialCase role = new CredentialCase(
                AwsCredentialType.ROLE_ARN.name(),
                "역할 위임 (Role ARN)",
                "만료 걱정 없음 — Qeploy 가 15분마다 새 임시 자격을 받아 씁니다",
                roleArnAvailable,
                roleArnAvailable,
                "키를 Qeploy 에 저장하지 않고, 사용자 계정의 역할을 Qeploy 가 위임받아 씁니다. 가장 안전합니다.",
                List.of(
                        "IAM > 정책 > 정책 생성에서 아래에서 고른 권한 정책을 붙여넣고 '" + POLICY_NAME + "' 로 저장",
                        "IAM > 역할 > 역할 생성 > '사용자 지정 신뢰 정책' 에 신뢰 정책을 붙여넣기",
                        "그 역할에 '" + POLICY_NAME + "' 을 연결하고 이름을 '" + ROLE_NAME + "' 로 저장",
                        "역할 상세의 ARN 과 계정 ID 를 연결 화면에 입력"),
                roleArnAvailable ? null
                        : "이 환경에서는 아직 준비되지 않아 고를 수 없습니다. 장기 액세스 키를 사용해주세요.");
        CredentialCase accessKey = new CredentialCase(
                AwsCredentialType.ACCESS_KEY.name(),
                "IAM 사용자 장기 액세스 키 (AKIA…)",
                "직접 비활성화·삭제할 때까지",
                true,
                !roleArnAvailable,
                "IAM 사용자의 액세스 키 ID(AKIA 로 시작)와 시크릿입니다. 시크릿은 암호화해 저장하고 응답에 내보내지 않습니다.",
                List.of(
                        "IAM > 정책 > 정책 생성에서 아래에서 고른 권한 정책을 붙여넣고 '" + POLICY_NAME + "' 로 저장",
                        "IAM > 사용자 > 사용자 생성(예: qeploy-deploy) > '직접 정책 연결' 로 '" + POLICY_NAME + "' 연결",
                        "그 사용자 > 보안 자격 증명 > 액세스 키 만들기 > 사용 사례 '서드 파티 서비스'",
                        "액세스 키 ID 와 시크릿을 연결 화면에 입력 — 시크릿은 이 화면에서만 보입니다"),
                "키를 교체하거나 지우면 연결이 끊깁니다. 새 연결을 만들지 말고 그 연결의 '자격 교체' 로 새 키를 넣어야 "
                        + "기존 DB·서버·CDN 을 계속 다룰 수 있습니다.");
        CredentialCase temporary = new CredentialCase(
                "TEMPORARY",
                "임시 자격 (ASIA… + 세션 토큰)",
                "15분 ~ 36시간 뒤 만료, 자동 갱신 안 됨",
                false,
                false,
                "AWS Academy·IAM Identity Center(SSO) 로그인·aws sts 명령이 주는 키입니다. 등록 직후에는 동작하지만 "
                        + "만료되면 배포·삭제가 모두 실패하므로 받지 않습니다.",
                List.of(),
                "이 키밖에 없다면 IAM 사용자를 만들 권한이 있는 계정에서 장기 키를 발급하거나 역할 위임을 사용해주세요.");
        return List.of(role, accessKey, temporary);
    }

    /**
     * 기능 묶음을 전체본의 문장으로 풀어 낸다. 묶음 파일은 Sid 만 가리키므로, 전체본에 없는 Sid 를
     * 가리키면 그 묶음을 고른 사용자의 정책에서 권한이 조용히 빠진다 — 그래서 여기서 바로 실패시킨다.
     */
    @SuppressWarnings("unchecked")
    private List<PolicyModule> policyModules() {
        Map<String, Map<String, Object>> bySid = new LinkedHashMap<>();
        for (Object statement : (List<Object>) policyDocumentLoader.loadDeployPolicy().get("Statement")) {
            Map<String, Object> s = (Map<String, Object>) statement;
            bySid.put((String) s.get("Sid"), s);
        }
        List<Object> modules = (List<Object>) policyDocumentLoader.loadPolicyModules().get("modules");
        return modules.stream().map(raw -> {
            Map<String, Object> m = (Map<String, Object>) raw;
            List<Map<String, Object>> statements = new java.util.ArrayList<>();
            for (Object sid : (List<Object>) m.getOrDefault("sids", List.of())) {
                Map<String, Object> statement = bySid.get(sid);
                if (statement == null) {
                    throw new IllegalStateException("권한 묶음 " + m.get("key") + " 이 전체본에 없는 Sid 를 가리킵니다: " + sid);
                }
                statements.add(statement);
            }
            for (Object extra : (List<Object>) m.getOrDefault("statements", List.of())) {
                statements.add((Map<String, Object>) extra);
            }
            return new PolicyModule(
                    (String) m.get("key"),
                    (String) m.get("label"),
                    (String) m.get("description"),
                    Boolean.TRUE.equals(m.get("required")),
                    Boolean.TRUE.equals(m.get("defaultSelected")),
                    List.copyOf(statements));
        }).toList();
    }

    private RequirementField field(String key, String label, String description, String whereToFind,
                                   String example, boolean required, boolean secret) {
        return new RequirementField(key, label, description, whereToFind, example, required, secret);
    }

    private SetupStep step(int order, String title, String detail) {
        return new SetupStep(order, title, detail);
    }
}

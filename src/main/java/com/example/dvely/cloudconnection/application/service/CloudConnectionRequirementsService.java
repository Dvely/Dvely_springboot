package com.example.dvely.cloudconnection.application.service;

import com.example.dvely.cloudconnection.application.result.CloudConnectionRequirementsResult;
import com.example.dvely.cloudconnection.application.result.CloudConnectionRequirementsResult.CredentialOption;
import com.example.dvely.cloudconnection.application.result.CloudConnectionRequirementsResult.RequirementField;
import com.example.dvely.cloudconnection.application.result.CloudConnectionRequirementsResult.SetupStep;
import com.example.dvely.cloudconnection.domain.value.AwsCredentialType;
import com.example.dvely.cloudconnection.domain.value.CloudProvider;
import com.example.dvely.cloudconnection.infrastructure.config.CloudConnectionGuideProperties;
import com.example.dvely.cloudconnection.infrastructure.external.AwsPolicyDocumentLoader;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 클라우드 연결 가이드를 조립한다. 추천 정책은 코드가 실제 호출하는 AWS 액션에서 도출한 리소스
 * 파일을 단일 소스로 서빙하고(하드코딩 금지 — BE 가 액션을 늘리면 정책도 같이 바뀌게), 필드·단계·주의는
 * 인증 방식별로 구성한다. 지금은 AWS 만 제공한다.
 */
@Service
@RequiredArgsConstructor
public class CloudConnectionRequirementsService {

    private static final String POLICY_NAME = "QeployDeployPolicy";
    private static final String ROLE_NAME = "QeployDeployRole";

    private final AwsPolicyDocumentLoader policyDocumentLoader;
    private final CloudConnectionGuideProperties guideProperties;

    public CloudConnectionRequirementsResult getRequirements(String provider, String credentialType) {
        CloudProvider cloudProvider = CloudProvider.from(provider);
        if (cloudProvider != CloudProvider.AWS) {
            throw new IllegalArgumentException("현재 AWS 연결 가이드만 제공합니다. provider=" + provider);
        }
        // 가이드는 방식 미지정이면 권장(ROLE_ARN)을 보여준다. AwsCredentialType.from 은 하위호환상 빈 값을
        // ACCESS_KEY 로 떨구므로 여기서 명시적으로 나눈다.
        AwsCredentialType type = (credentialType == null || credentialType.isBlank())
                ? AwsCredentialType.ROLE_ARN
                : AwsCredentialType.from(credentialType);
        return type == AwsCredentialType.ROLE_ARN ? roleArnGuide() : accessKeyGuide();
    }

    private CloudConnectionRequirementsResult roleArnGuide() {
        return new CloudConnectionRequirementsResult(
                CloudProvider.AWS.name(),
                AwsCredentialType.ROLE_ARN.name(),
                AwsCredentialType.ROLE_ARN.name(),
                credentialOptions(),
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
                notes(true)
        );
    }

    private CloudConnectionRequirementsResult accessKeyGuide() {
        return new CloudConnectionRequirementsResult(
                CloudProvider.AWS.name(),
                AwsCredentialType.ACCESS_KEY.name(),
                AwsCredentialType.ROLE_ARN.name(),
                credentialOptions(),
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
                                "액세스 키 생성 시 함께 발급 (그때만 확인 가능)", "****", true, true),
                        field("sessionToken", "세션 토큰", "ASIA 로 시작하는 임시 자격일 때만 입력합니다. AKIA 장기 키는 비워둡니다.",
                                "임시 자격(STS)일 때만", "", false, true)
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
                notes(false)
        );
    }

    private List<CredentialOption> credentialOptions() {
        return List.of(
                new CredentialOption(AwsCredentialType.ROLE_ARN.name(), "역할 위임 (Role ARN)", true,
                        "장기 키를 Qeploy 에 저장하지 않습니다. 사용자가 만든 역할을 필요할 때만 위임받아 씁니다. 권장."),
                new CredentialOption(AwsCredentialType.ACCESS_KEY.name(), "액세스 키", false,
                        "설정이 더 간단하지만 장기 키를 암호화해 저장합니다. 임시 자격(STS)을 쓸 수도 있습니다.")
        );
    }

    private List<String> notes(boolean roleArn) {
        String platformNote = roleArn
                ? (guideProperties.hasPlatformPrincipalArn()
                    ? "신뢰 정책의 Principal 은 이 역할을 위임받는 Qeploy 컨트롤 플레인의 identity 입니다 — 그대로 두세요."
                    : "신뢰 정책의 Principal 이 아직 설정되지 않아 placeholder 로 표시됩니다. 운영 환경에서 실제 Qeploy "
                            + "플랫폼 principal ARN 을 채워야 역할 위임이 동작합니다.")
                : null;
        return java.util.stream.Stream.of(
                "연결 검증은 자격 유효성(STS GetCallerIdentity)만 확인합니다. 위 권한 정책이 빠지면 연결은 "
                        + "성공해도 실제 배포에서 권한 부족으로 실패합니다 — 그래서 정책을 먼저 붙여야 합니다.",
                "권한 정책은 S3(qeploy-* 버킷)·SSM 파라미터(/qeploy/*)·IAM 인스턴스 역할(qeploy-instance-*)로 "
                        + "리소스를 좁혀 최소권한을 지향합니다.",
                "Docker 이미지 전송을 ECR 모드로 켠 경우에만 ECR 권한(ecr:CreateRepository, "
                        + "ecr:GetAuthorizationToken, ecr:DeleteRepository 및 이미지 push 액션)을 추가하세요. 기본 S3 "
                        + "모드는 필요 없습니다.",
                platformNote
        ).filter(java.util.Objects::nonNull).toList();
    }

    private RequirementField field(String key, String label, String description, String whereToFind,
                                   String example, boolean required, boolean secret) {
        return new RequirementField(key, label, description, whereToFind, example, required, secret);
    }

    private SetupStep step(int order, String title, String detail) {
        return new SetupStep(order, title, detail);
    }
}

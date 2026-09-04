# AWS BYOC 권한 (C2 백엔드 운영 배포)

Qeploy 는 사용자 AWS 계정(BYOC)에 백엔드 서버(EC2)를 띄운다. 우리는 사용자가 클라우드 연결 시
넘겨준 자격(Access Key 또는 Role ARN)으로 assume-role 하여 작업한다. **admin 권한을 요구하지
않는다** — 아래 최소권한만 있으면 된다. 권한이 모자라면 프로비저닝이 `IAM_PERMISSION` 코드로
명확히 실패하고, 사용자에게 이 정책을 안내한다.

> 이 문서는 개발자·보안 검토용이다. 비전문가용 온보딩(무엇을 클릭하는지)은 별도 가이드 참고.

## 설계 원칙

- **인스턴스 IAM 역할은 우리가 자동 생성**하되 이름 `qeploy-instance-*` 로 스코프한다 — 사용자가 IAM 을
  손으로 만들 필요가 없고(비전문가 진입장벽 제거), 우리 권한은 그 이름 접두사 안으로 묶인다(blast radius
  제한). **경로(`/qeploy/`)가 아니라 이름 접두사인 이유:** 생성 전 존재확인 `getRole(name)` 이 아직
  없는 역할을 평면 ARN(`role/qeploy-instance-{id}`)으로 권한평가하는데, 경로 ARN(`role/qeploy/*`)만
  허용하면 그 Get 이 거부된다. 실계정 검증(2026-09-03)에서 확인 — 이름 접두사는 생성·조회 ARN 이
  항상 평면으로 일치해 이 함정을 피한다.
- **비밀은 SSM SecureString** 에만 둔다. user-data·로그·AMI 어디에도 평문 없음. 인스턴스는 자기
  파라미터(`/qeploy/{projectId}/*`)만 읽는다.
- **IMDSv2 강제, SSH 없음, SG 는 앱 포트(8080)만.**

## 최소권한 정책 (코드 대조 확정 2026-09-03)

우리가 assume 하는 역할/사용자에 붙일 정책. 리소스는 가능한 한 `qeploy` 접두사·경로로 좁힌다.

```jsonc
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "Ec2LifecycleReadOnlyDescribe",
      "Effect": "Allow",
      "Action": [
        "ec2:DescribeInstances", "ec2:DescribeImages", "ec2:DescribeSubnets",
        "ec2:DescribeVpcs", "ec2:DescribeSecurityGroups", "ec2:DescribeAddresses"
      ],
      "Resource": "*"                       // Describe 계열은 리소스 스코프 불가(AWS 제약)
    },
    {
      "Sid": "Ec2RunTerminateTagged",
      "Effect": "Allow",
      "Action": ["ec2:RunInstances", "ec2:TerminateInstances", "ec2:CreateTags",
                 "ec2:CreateSecurityGroup", "ec2:AuthorizeSecurityGroupIngress",
                 "ec2:AllocateAddress", "ec2:AssociateAddress", "ec2:ReleaseAddress"],
      "Resource": "*"                       // 태그 조건으로 좁힐 예정(managed-by=qeploy). EIP=안정 주소,
                                            // ReleaseAddress 없으면 종료 후 유휴 EIP 가 계속 과금됨
    },
    {
      "Sid": "PassOnlyQeployInstanceRole",
      "Effect": "Allow",
      "Action": "iam:PassRole",
      "Resource": "arn:aws:iam::*:role/qeploy-instance-*"   // qeploy-instance-* 역할만 넘길 수 있음
    },
    {
      "Sid": "CreateQeployInstanceRoleScoped",
      "Effect": "Allow",
      "Action": ["iam:CreateRole", "iam:PutRolePolicy", "iam:CreateInstanceProfile",
                 "iam:AddRoleToInstanceProfile", "iam:GetRole", "iam:GetInstanceProfile"],
      "Resource": ["arn:aws:iam::*:role/qeploy-instance-*", "arn:aws:iam::*:instance-profile/qeploy-instance-*"]
    },
    {
      "Sid": "SsmProjectParamsOnly",
      "Effect": "Allow",
      "Action": ["ssm:PutParameter", "ssm:GetParameter", "ssm:GetParameters",
                 "ssm:GetParametersByPath", "ssm:DeleteParameter", "ssm:DeleteParameters"],
      "Resource": "arn:aws:ssm:*:*:parameter/qeploy/*"
    },
    {
      "Sid": "SsmPublicAmiRead",
      "Effect": "Allow",
      "Action": "ssm:GetParameter",
      "Resource": "arn:aws:ssm:*::parameter/aws/service/ami-amazon-linux-latest/*"   // AWS 공개 파라미터(계정 없음): 최신 AL2023 AMI 조회
    },
    {
      "Sid": "S3ArtifactsOnly",
      "Effect": "Allow",
      "Action": ["s3:CreateBucket", "s3:PutObject", "s3:GetObject", "s3:DeleteObject",
                 "s3:ListBucket", "s3:AbortMultipartUpload"],
      "Resource": ["arn:aws:s3:::qeploy-artifacts-*", "arn:aws:s3:::qeploy-artifacts-*/*"]
      // 대용량 산출물은 멀티파트 업로드(#218). Create/Upload/Complete 는 PutObject 로 커버되고,
      // 실패 시 정리(abortMultipartUpload)에 AbortMultipartUpload 가 필요하다(best-effort).
    },
    {
      "Sid": "RdsCreateDeleteQeployScoped",
      "Effect": "Allow",
      "Action": ["rds:CreateDBInstance", "rds:DeleteDBInstance"],
      "Resource": "arn:aws:rds:*:*:db:qeploy-*"   // 식별자 qeploy-{projectId}-{rand}
    },
    {
      "Sid": "RdsDescribe",
      "Effect": "Allow",
      "Action": "rds:DescribeDBInstances",
      "Resource": "*"                       // Describe 계열은 리소스 스코프 불가(AWS 제약)
    },
    {
      "Sid": "RdsServiceLinkedRole",
      "Effect": "Allow",
      "Action": "iam:CreateServiceLinkedRole",
      "Resource": "arn:aws:iam::*:role/aws-service-role/rds.amazonaws.com/AWSServiceRoleForRDS",
      "Condition": { "StringEquals": { "iam:AWSServiceName": "rds.amazonaws.com" } }
      // RDS 를 처음 쓰는 계정은 첫 CreateDBInstance 가 AWSServiceRoleForRDS(서비스 연결 역할)를
      // 자동 생성한다. 그 권한이 없으면 "permission to create service linked role" 로 400 실패한다
      // (실계정 검증 2026-09-03). 조건으로 rds.amazonaws.com SLR 하나로만 좁혀 blast radius 를 막는다.
      // 이미 RDS 를 써본 계정은 SLR 이 있어 이 액션이 호출되지 않는다.
    },
    {
      "Sid": "EcrAuthToken",
      "Effect": "Allow",
      "Action": "ecr:GetAuthorizationToken",
      "Resource": "*"                       // 계정 레벨 토큰(AWS 제약상 스코프 불가)
    },
    {
      "Sid": "EcrPushManageQeployScoped",
      "Effect": "Allow",
      "Action": ["ecr:CreateRepository", "ecr:DescribeRepositories", "ecr:DeleteRepository",
                 "ecr:BatchCheckLayerAvailability", "ecr:InitiateLayerUpload",
                 "ecr:UploadLayerPart", "ecr:CompleteLayerUpload", "ecr:PutImage",
                 "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer"],
      "Resource": "arn:aws:ecr:*:*:repository/qeploy-app-*"   // 이미지 저장소 qeploy-app-{projectId}
      // DOCKER 배포의 image-transfer=ECR 경로 전용: 컨트롤 플레인이 이미지를 ECR 로 push 한다.
      // buildx(buildkit)는 push 전에 매니페스트를 HEAD 로 확인하므로 read 권한(BatchGetImage,
      // GetDownloadUrlForLayer)도 필요하다 — 없으면 매니페스트 HEAD 가 403 으로 push 실패한다
      // (실계정 e2e 2026-09-04 에서 확인 — 고전 docker push 는 이 read-check 가 없어 안 드러났다).
      // 기본 S3 전달이면 이 권한은 불필요. EC2 의 pull 권한은 인스턴스 역할(아래)에 자동 부여.
    }
  ]
}
```

> **런타임 검증 상태(2026-09-03):** EC2·IAM·EIP·SSM·S3 경로는 개인 계정 실배포로 검증 완료.
> RDS 경로 검증 중 두 실계정 함정을 잡았다: (1) RDS 전용 SG(`qeploy-db`)를 명시하지 않으면 기본
> SG 가 붙어 `qeploy-backend` SG 의 EC2 가 3306 에 못 붙는다 → `ensureDatabaseSecurityGroup`
> 추가로 해결. (2) 처음 RDS 를 쓰는 계정은 서비스 연결 역할 자동 생성에 `iam:CreateServiceLinkedRole`
> 이 필요하다 → 위 `RdsServiceLinkedRole` 로 해결. 지난 e2e 는 AWS Academy 의 넓은 키였다 — 그때
> RDS 가 된 것은 키가 넓어서였지 이 정책 때문이 아니다.

## 인스턴스 자신이 받는 역할 (우리가 `/qeploy/` 아래 생성)

인스턴스는 자기 배포에 필요한 것만 읽는다:

```jsonc
{
  "Version": "2012-10-17",
  "Statement": [
    { "Effect": "Allow", "Action": ["ssm:GetParameter", "ssm:GetParameters"],
      "Resource": "arn:aws:ssm:*:*:parameter/qeploy/{projectId}/*" },
    { "Effect": "Allow", "Action": ["s3:GetObject"],
      "Resource": "arn:aws:s3:::qeploy-artifacts-*/{projectId}/*" }
    // image-transfer=ECR 이면 아래 두 문장이 추가된다(우리가 자동 부여, 사용자 조치 불필요):
    // { "Effect": "Allow", "Action": "ecr:GetAuthorizationToken", "Resource": "*" },
    // { "Effect": "Allow", "Action": ["ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer",
    //     "ecr:BatchCheckLayerAvailability"], "Resource": "arn:aws:ecr:*:*:repository/qeploy-app-{projectId}" }
  ]
}
```

> 인스턴스 역할은 자기 프로젝트 경로만 참조하므로, 앱이 탈취돼도 다른 프로젝트의 비밀·아티팩트에
> 닿지 못한다. ECR pull 권한도 자기 프로젝트 저장소(`qeploy-app-{projectId}`)로만 좁힌다.

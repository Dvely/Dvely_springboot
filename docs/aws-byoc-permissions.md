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
        "ec2:DescribeVpcs", "ec2:DescribeSecurityGroups"
      ],
      "Resource": "*"                       // Describe 계열은 리소스 스코프 불가(AWS 제약)
    },
    {
      "Sid": "Ec2RunTerminateTagged",
      "Effect": "Allow",
      "Action": ["ec2:RunInstances", "ec2:TerminateInstances", "ec2:CreateTags",
                 "ec2:CreateSecurityGroup", "ec2:AuthorizeSecurityGroupIngress"],
      "Resource": "*"                       // 태그 조건으로 좁힐 예정(managed-by=qeploy)
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
                 "s3:ListBucket"],
      "Resource": ["arn:aws:s3:::qeploy-artifacts-*", "arn:aws:s3:::qeploy-artifacts-*/*"]
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
    }
  ]
}
```

> **런타임 검증 상태(2026-09-03):** 위 액션·리소스 스코프는 실제 호출부(`Ec2Provisioner`,
> `S3ArtifactStore`, `SsmParameterStore`, `Ec2InstanceRoleProvisioner`, `RdsProvisioner`)와
> 1:1 대조해 확정했다. 다만 **이 최소권한 키만으로 실배포가 도는지의 e2e 검증은 EC2·IAM 경로가
> 오늘(A) 진행 중이고, RDS 경로는 아직 미검증이다.** 지난 e2e 는 AWS Academy 의 넓은 키였다 —
> 그때 RDS 가 된 것은 키가 넓어서였지 이 정책 때문이 아니다.

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
  ]
}
```

> 인스턴스 역할은 자기 프로젝트 경로만 참조하므로, 앱이 탈취돼도 다른 프로젝트의 비밀·아티팩트에
> 닿지 못한다.

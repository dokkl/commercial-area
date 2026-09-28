# 운영 배포 (Lightsail + Docker Compose + GitHub Actions)

```
GitHub push(main) ─▶ Actions: test ─▶ build(GHCR 푸시) ─▶ deploy(SSH)
                                                          │
Lightsail 4GB (서울) ◀──────────────────────────────────────┘
 └─ docker compose: caddy(:80/:443) → app(:8080) → mysql(볼륨)
```

| 경로 | 역할 |
|---|---|
| `infra/terraform/` | 인스턴스, 고정 IP, 방화벽(22/80/443), 자동 스냅샷 |
| `deploy/docker-compose.prod.yml` | 운영 compose (서버에 `docker-compose.yml`로 복사됨) |
| `deploy/Caddyfile` | 리버스 프록시 + 도메인 설정 시 HTTPS 자동 발급 |
| `.github/workflows/deploy.yml` | 테스트 → 이미지 빌드 → 배포 → 헬스체크 |
| `scripts/upload-csv.sh` | CSV 업로드 후 app 재시작(적재 시작) |

## 1. 인프라 생성 (최초 1회)

```bash
ssh-keygen -t ed25519 -f ~/.ssh/commercial-area -C commercial-area-deploy -N ""

cd infra/terraform
cp terraform.tfvars.example terraform.tfvars   # 필요 시 aws_profile 등 수정
terraform init
terraform apply
terraform output public_ip
```

부트스트랩(Docker 설치 등)은 1~3분 걸린다. 완료 확인:

```bash
ssh -i ~/.ssh/commercial-area ubuntu@<IP> 'test -f /var/lib/cloud/instance/bootstrap-done && docker compose version'
```

## 2. GitHub 설정

Settings → Environments → `production` 을 만들고 아래를 등록한다.

| 종류 | 이름 | 값 |
|---|---|---|
| Secret | `LIGHTSAIL_HOST` | `terraform output -raw public_ip` |
| Secret | `LIGHTSAIL_SSH_KEY` | `cat ~/.ssh/commercial-area` (개인키 전체) |
| Secret | `LIGHTSAIL_KNOWN_HOSTS` | `ssh-keyscan -H <IP>` 출력 |
| Secret | `MYSQL_PASSWORD` | 임의의 강한 비밀번호 |
| Secret | `MYSQL_ROOT_PASSWORD` | 임의의 강한 비밀번호 |
| Variable | `SNAPSHOT` | 올린 CSV의 기준 분기(YYYYMM), 예: `202606`. 필수 |
| Variable | `IMPORT_INCLUDE` | (선택) 기본 `서울,경기` |
| Variable | `SITE_ADDRESS` | (선택) `map.example.com` — 비우면 `:80` HTTP |

> MySQL 비밀번호는 **최초 기동 때 볼륨에 고정**된다. 나중에 Secret만 바꾸면 app이 접속하지 못한다.
> 바꾸려면 MySQL에서 `ALTER USER`를 먼저 실행한다.

## 3. 배포와 데이터 적재

1. `main`에 push → Actions가 배포한다. (수동: Actions 탭 → deploy → Run workflow)
2. CSV를 올린다. app이 재시작되며 적재가 시작된다(서울·경기 기준 수 분).

```bash
scripts/upload-csv.sh <IP>              # .env의 CSV_DIR 사용
ssh -i ~/.ssh/commercial-area ubuntu@<IP> 'cd /opt/commercial-area && docker compose logs -f app'
```

CSV를 먼저 올려도 된다. 그 경우 첫 배포 때 자동으로 적재된다.

## 도메인 / HTTPS

DNS A 레코드를 고정 IP로 지정한 뒤 GitHub Variable `SITE_ADDRESS`에 도메인을 넣고 재배포하면
Caddy가 Let's Encrypt 인증서를 자동 발급·갱신한다.

## 운영 메모

- **MySQL 접속**: `ssh -i ~/.ssh/commercial-area -L 3307:127.0.0.1:3307 ubuntu@<IP>` 후 로컬 3307로 접속
- **백업**: Lightsail 자동 스냅샷(매일 03:00 KST, 7일 보관). 논리 백업이 필요하면 `mysqldump`를 cron으로 S3에 올린다.
- **롤백**: 이미지 태그가 커밋 SHA이므로 서버 `.env`의 `APP_IMAGE`를 이전 SHA로 바꾸고 `docker compose up -d`
- **인스턴스를 재생성하는 변경 주의**: `bundle_id`, `blueprint_id`, 키 페어 변경은 인스턴스를 교체해 MySQL 데이터가 사라진다.
  크기를 키우려면 스냅샷에서 더 큰 번들로 새 인스턴스를 만드는 방식으로 진행한다.
- **인증 없음**: 이 앱은 인증이 없어 URL을 아는 누구나 접근할 수 있다. 공개 전 필요하면 Caddyfile에 `basic_auth`를 추가한다.

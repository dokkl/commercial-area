#!/usr/bin/env bash
# 로컬 CSV를 Lightsail 서버로 올리고 app을 재시작해 적재를 시작한다.
#
# 사용법: scripts/upload-csv.sh <서버 IP> [CSV 디렉토리]
#   CSV 디렉토리를 생략하면 .env의 CSV_DIR을 사용한다.
#   SSH 키는 SSH_KEY 환경변수로 바꿀 수 있다 (기본 ~/.ssh/commercial-area).
#
# 적재는 store 테이블이 비어 있을 때만 일어난다. 이미 적재된 상태에서 지역을 바꾸려면
# 서버에서 `docker compose down -v`로 볼륨을 지운 뒤 다시 올려야 한다.
set -euo pipefail

HOST="${1:?사용법: $0 <서버 IP> [CSV 디렉토리]}"
CSV_DIR="${2:-}"
if [ -z "$CSV_DIR" ] && [ -f .env ]; then
  CSV_DIR="$(grep -E '^CSV_DIR=' .env | cut -d= -f2-)"
fi
: "${CSV_DIR:?CSV 디렉토리를 인자로 주거나 .env에 CSV_DIR을 설정하세요}"

SSH_KEY="${SSH_KEY:-$HOME/.ssh/commercial-area}"
APP_DIR="/opt/commercial-area"
SSH=(ssh -i "$SSH_KEY" "ubuntu@$HOST")

echo "업로드: $CSV_DIR → $HOST:$APP_DIR/csv/"
rsync -avz --progress -e "ssh -i $SSH_KEY" \
  --include='*.csv' --exclude='*' \
  "$CSV_DIR/" "ubuntu@$HOST:$APP_DIR/csv/"

if "${SSH[@]}" "test -f $APP_DIR/docker-compose.yml"; then
  echo "app 재시작 — 적재 로그: ssh -i $SSH_KEY ubuntu@$HOST 'cd $APP_DIR && docker compose logs -f app'"
  "${SSH[@]}" "cd $APP_DIR && docker compose restart app"
else
  echo "아직 배포 전입니다. 첫 배포가 끝나면 app이 기동하면서 자동으로 적재합니다."
fi

#!/bin/sh
# Lightsail 최초 부팅 시 root로 1회 실행된다. (terraform templatefile로 렌더링됨)
# 진행 로그: /var/log/cloud-init-output.log
# Lightsail이 자체 #!/bin/sh 스크립트 뒤에 이어 붙여 sh(dash)로 실행하므로 POSIX sh 문법만 쓴다.
set -eux
export DEBIAN_FRONTEND=noninteractive

timedatectl set-timezone Asia/Seoul

# 스왑 — CSV 적재 중 메모리 압박 시 OOM 대신 느려지도록
if [ ! -f /swapfile ]; then
  fallocate -l ${swap_size_gb}G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
  echo 'vm.swappiness=10' > /etc/sysctl.d/99-swappiness.conf
  sysctl --system
fi

# Docker + Compose v2 (Ubuntu 공식 패키지)
apt-get update
apt-get install -y ca-certificates curl rsync docker.io docker-compose-v2
systemctl enable --now docker
usermod -aG docker ubuntu

# 애플리케이션 디렉토리
mkdir -p ${app_dir}/csv
chown -R ubuntu:ubuntu ${app_dir}

touch /var/lib/cloud/instance/bootstrap-done

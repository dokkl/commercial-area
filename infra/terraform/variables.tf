variable "name" {
  description = "Lightsail 리소스 이름 접두어"
  type        = string
  default     = "commercial-area"
}

variable "region" {
  description = "AWS 리전"
  type        = string
  default     = "ap-northeast-2"
}

variable "aws_profile" {
  description = "로컬 AWS CLI 프로파일 이름 (null이면 기본 자격 증명 체인)"
  type        = string
  default     = null
}

variable "availability_zone_suffix" {
  description = "가용 영역 접미사 (a, b, c ...)"
  type        = string
  default     = "a"
}

variable "blueprint_id" {
  description = "OS 이미지. 목록: aws lightsail get-blueprints --region ap-northeast-2"
  type        = string
  default     = "ubuntu_24_04"
}

variable "bundle_id" {
  description = <<-EOT
    인스턴스 크기. medium_3_0 = 4GB / 2 vCPU.
    MySQL buffer pool 1G + JVM + Caddy를 한 머신에 올리므로 4GB를 기본으로 한다.
    목록: aws lightsail get-bundles --region ap-northeast-2
  EOT
  type        = string
  default     = "medium_3_0"
}

variable "ssh_public_key_path" {
  description = "서버 접속·배포에 쓸 SSH 공개키 경로"
  type        = string
  default     = "~/.ssh/commercial-area.pub"
}

variable "ssh_allowed_cidrs" {
  description = <<-EOT
    22번 포트 허용 대역. GitHub Actions 러너 IP가 고정되지 않으므로 기본은 전체 허용
    (비밀번호 로그인은 Lightsail Ubuntu에서 기본 비활성, 키 인증만 가능).
  EOT
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "app_dir" {
  description = "서버의 애플리케이션 디렉토리 (compose, .env, csv)"
  type        = string
  default     = "/opt/commercial-area"
}

variable "swap_size_gb" {
  description = "스왑 파일 크기(GB). 적재 중 순간적인 메모리 압박 대비"
  type        = number
  default     = 2
}

variable "auto_snapshot_time" {
  description = "자동 스냅샷 시각 (UTC, HH:00). 18:00 UTC = 03:00 KST"
  type        = string
  default     = "18:00"
}

variable "domain_name" {
  description = "Route 53 호스팅 존이 있는 도메인 (예: seeplus.net). apex와 www A 레코드를 고정 IP로 만든다. null이면 생략"
  type        = string
  default     = null
}

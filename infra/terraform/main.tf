locals {
  tags = {
    Project   = var.name
    ManagedBy = "terraform"
  }
}

resource "aws_lightsail_key_pair" "this" {
  name       = "${var.name}-key"
  public_key = file(pathexpand(var.ssh_public_key_path))
  tags       = local.tags
}

resource "aws_lightsail_instance" "this" {
  name              = var.name
  availability_zone = "${var.region}${var.availability_zone_suffix}"
  blueprint_id      = var.blueprint_id
  bundle_id         = var.bundle_id
  key_pair_name     = aws_lightsail_key_pair.this.name

  user_data = templatefile("${path.module}/user_data.sh", {
    app_dir      = var.app_dir
    swap_size_gb = var.swap_size_gb
  })

  add_on {
    type          = "AutoSnapshot"
    snapshot_time = var.auto_snapshot_time
    status        = "Enabled"
  }

  tags = local.tags

  lifecycle {
    # user_data 변경은 인스턴스 재생성(= MySQL 데이터 유실)을 일으킨다.
    # 부트스트랩은 최초 1회만 의미가 있으므로 이후 변경은 무시한다.
    ignore_changes = [user_data]
  }
}

resource "aws_lightsail_static_ip" "this" {
  name = "${var.name}-ip"
}

resource "aws_lightsail_static_ip_attachment" "this" {
  static_ip_name = aws_lightsail_static_ip.this.name
  instance_name  = aws_lightsail_instance.this.name
}

# 이 리소스는 인스턴스의 방화벽 규칙 전체를 대체한다. 22번도 여기서 명시해야 열린다.
resource "aws_lightsail_instance_public_ports" "this" {
  instance_name = aws_lightsail_instance.this.name

  port_info {
    protocol  = "tcp"
    from_port = 22
    to_port   = 22
    cidrs     = var.ssh_allowed_cidrs
  }

  port_info {
    protocol  = "tcp"
    from_port = 80
    to_port   = 80
    cidrs     = ["0.0.0.0/0"]
  }

  port_info {
    protocol  = "tcp"
    from_port = 443
    to_port   = 443
    cidrs     = ["0.0.0.0/0"]
  }

  # HTTP/3
  port_info {
    protocol  = "udp"
    from_port = 443
    to_port   = 443
    cidrs     = ["0.0.0.0/0"]
  }
}

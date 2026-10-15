output "public_ip" {
  description = "고정 IP — GitHub Secret LIGHTSAIL_HOST에 등록"
  value       = aws_lightsail_static_ip.this.ip_address
}

output "ssh_command" {
  value = "ssh -i ${trimsuffix(pathexpand(var.ssh_public_key_path), ".pub")} ubuntu@${aws_lightsail_static_ip.this.ip_address}"
}

output "app_url" {
  value = "http://${aws_lightsail_static_ip.this.ip_address}"
}

output "site_address" {
  description = "GitHub Variable SITE_ADDRESS에 넣을 값 (Caddy가 HTTPS 인증서를 발급할 주소)"
  value       = var.domain_name == null ? null : "${var.domain_name}, www.${var.domain_name}"
}

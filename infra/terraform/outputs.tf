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

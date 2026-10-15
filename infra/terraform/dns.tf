# domain_name을 비우면 DNS 레코드를 만들지 않는다(IP로만 접속).
# 호스팅 존은 Route 53에서 도메인을 구매할 때 자동 생성되므로 data로 참조만 한다.
data "aws_route53_zone" "this" {
  count = var.domain_name == null ? 0 : 1
  name  = var.domain_name
}

resource "aws_route53_record" "apex" {
  count   = var.domain_name == null ? 0 : 1
  zone_id = data.aws_route53_zone.this[0].zone_id
  name    = var.domain_name
  type    = "A"
  ttl     = 300
  records = [aws_lightsail_static_ip.this.ip_address]
}

resource "aws_route53_record" "www" {
  count   = var.domain_name == null ? 0 : 1
  zone_id = data.aws_route53_zone.this[0].zone_id
  name    = "www.${var.domain_name}"
  type    = "A"
  ttl     = 300
  records = [aws_lightsail_static_ip.this.ip_address]
}

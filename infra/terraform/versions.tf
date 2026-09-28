terraform {
  required_version = ">= 1.5"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.0"
    }
  }

  # 상태 파일을 S3에 보관하려면 주석을 해제한다. (기본은 로컬 terraform.tfstate)
  # use_lockfile은 Terraform 1.10+ 전용이다. 1.5에서는 dynamodb_table로 잠금을 건다.
  # backend "s3" {
  #   bucket       = "<state-bucket>"
  #   key          = "commercial-area/terraform.tfstate"
  #   region       = "ap-northeast-2"
  #   use_lockfile = true
  # }
}

provider "aws" {
  region  = var.region
  profile = var.aws_profile
}

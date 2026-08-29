variable "name_prefix" {
  type = string
}

variable "repositories" {
  description = "One per deployable image."
  type        = list(string)
  default     = ["learning-platform", "ramals-ai"]
}

variable "kms_key_arn" {
  description = "Key for image encryption at rest."
  type        = string
}

variable "retained_image_count" {
  description = "Tagged images kept per repository. Each one is a rollback target."
  type        = number
  default     = 20
}

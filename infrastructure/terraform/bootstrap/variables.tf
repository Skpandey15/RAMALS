variable "region" {
  description = "State lives in the same region as the estate it describes."
  type        = string
  default     = "ap-south-1"
}

variable "name_prefix" {
  type    = string
  default = "ramals"
}

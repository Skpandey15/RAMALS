# One repository per deployable image.
#
# Separate repositories rather than one with tag prefixes, so that a policy -- who may push, what is
# scanned, what is retained -- can differ per image without a naming convention holding it together.

resource "aws_ecr_repository" "this" {
  for_each = toset(var.repositories)

  name                 = "${var.name_prefix}/${each.value}"
  image_tag_mutability = "IMMUTABLE"

  # Immutable tags are the deployment-integrity control. With mutable tags, "deploy the image the
  # pipeline built" and "deploy whatever is called that now" are the same command with different
  # outcomes, and a rollback to a tag can land on content that has since been overwritten.

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "KMS"
    kms_key         = var.kms_key_arn
  }

  tags = { Name = "${var.name_prefix}-${each.value}" }
}

# Untagged layers accumulate from every rebuilt image and are never deployable. Tagged images are
# kept generously: a rollback target that has been garbage-collected is not a rollback target.
resource "aws_ecr_lifecycle_policy" "this" {
  for_each = aws_ecr_repository.this

  repository = each.value.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Expire untagged layers quickly; they are never deployable"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 3
        }
        action = { type = "expire" }
      },
      {
        rulePriority = 2
        description  = "Keep the last ${var.retained_image_count} tagged images as rollback targets"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = var.retained_image_count
        }
        action = { type = "expire" }
      },
    ]
  })
}

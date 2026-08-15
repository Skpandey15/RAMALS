package io.ramals.learningplatform.admin;

/** Raised when a content version does not exist or cannot be parsed. */
public class ContentVersionNotFoundException extends RuntimeException {

  public ContentVersionNotFoundException(String curriculumVersionId) {
    super("Curriculum version was not found: " + curriculumVersionId);
  }
}

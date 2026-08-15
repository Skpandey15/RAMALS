package io.ramals.learningplatform.admin;

/** Raised when publishing is rejected by the database's content-integrity rules. */
public class ContentPublicationException extends RuntimeException {

  public ContentPublicationException(String curriculumVersionId) {
    super("Curriculum version could not be published: " + curriculumVersionId);
  }
}

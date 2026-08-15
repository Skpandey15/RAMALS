package io.ramals.learningplatform.curriculum;

public class CurriculumNotFoundException extends RuntimeException {

  public CurriculumNotFoundException(String domainCode, String versionCode) {
    super("Curriculum version was not found: " + domainCode + "/" + versionCode);
  }
}

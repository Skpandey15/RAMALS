package io.ramals.learningplatform.assessment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How a diagnostic form is assembled from the item pool.
 *
 * <p>Both settings have working defaults. Selection is not an opt-in capability -- every attempt
 * goes through it -- so a deployment that configures nothing must still assemble a sane form
 * rather than fail to start or fall back to serving the whole pool.
 */
@ConfigurationProperties("ramals.diagnostic.form")
public class DiagnosticFormProperties {

  /**
   * How many items a form should contain.
   *
   * <p>A target rather than a guarantee, in both directions. A pool smaller than this yields a
   * shorter form, and a pool spanning more skills or difficulty bands than this yields a longer
   * one -- see {@link DiagnosticFormSelector} for why coverage outranks size.
   */
  private int targetSize = 10;

  /**
   * How far back an item counts as recently seen by this learner.
   *
   * <p>Only ever de-prioritizes an item; nothing is excluded on this basis. Zero disables the
   * preference entirely, which makes every form an unbiased draw from the pool.
   */
  private int recencyWindowDays = 90;

  public int getTargetSize() {
    return targetSize;
  }

  public void setTargetSize(int targetSize) {
    this.targetSize = targetSize;
  }

  public int getRecencyWindowDays() {
    return recencyWindowDays;
  }

  public void setRecencyWindowDays(int recencyWindowDays) {
    this.recencyWindowDays = recencyWindowDays;
  }
}

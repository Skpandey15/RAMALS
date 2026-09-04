package io.ramals.learningplatform.assessment;

import java.util.List;
import java.util.Set;

/**
 * An assembled adaptive form, plus what the assembly can report about itself.
 *
 * <p>{@code skillsWithNoUnseenStock} is the explicit bank-exhaustion report the no-repeat
 * requirement calls for: a skill present in the version's pool but absent here contributed zero
 * items not because it did not need coverage, but because every one of its items had already been
 * shown to this learner. The caller decides what to do with that -- log it, surface it, or (when
 * the packet is empty outright) refuse the attempt -- but this record makes the fact impossible to
 * miss rather than indistinguishable from "this skill simply was not needed".
 */
public record AdaptivePacket(
    List<SelectedItem> items,
    int poolSize,
    int skillsCovered,
    int singleChoiceCount,
    int fillBlankCount,
    Set<String> skillsWithNoUnseenStock) {
}

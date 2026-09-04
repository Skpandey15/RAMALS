package io.ramals.learningplatform.assessment;

import java.util.List;

/**
 * Internal shape of answer_key_jsonb. Never serialized to a learner.
 *
 * <p>Shaped by item type, per V047's {@code ck_assessment_item_answer_key} constraint:
 * {@code correct} for SINGLE_CHOICE, {@code accepted} for FILL_BLANK. Both fields are declared so
 * Jackson can deserialize either row shape without an unrecognized-property failure; whichever the
 * item's type does not use is simply absent from that row's JSON and null here. SHORT_ANSWER and
 * USE_CASE carry a {@code rubric} object instead of either -- this type is never asked to
 * deserialize one, because the repository never selects those rows into a scoring query.
 */
record AnswerKey(List<String> correct, List<String> accepted) {
}

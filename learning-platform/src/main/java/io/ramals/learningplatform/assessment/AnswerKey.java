package io.ramals.learningplatform.assessment;

import java.util.List;

/** Internal shape of answer_key_jsonb. Never serialized to a learner. */
record AnswerKey(List<String> correct) {
}

package com.fakejira.task;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MentionParserTest {

    @Test
    void findsMentionsButNotEmails() {
        assertThat(MentionParser.usernames("Hey @Bob.Smith, ping @carol_1. mail me at dave@example.com @x"))
                .containsExactly("bob.smith", "carol_1");
    }

    @Test
    void mentionAtStartAndAfterPunctuation() {
        assertThat(MentionParser.usernames("@alice (@bob) \"@carol\"")).containsExactly("alice", "bob", "carol");
    }
}

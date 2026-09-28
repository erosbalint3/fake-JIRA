package com.fakejira.task;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AttachmentFilenameTest {

    @Test
    void stripsPathsQuotesAndControlCharacters() {
        assertThat(AttachmentController.cleanFilename("C:\\Users\\me\\report.pdf")).isEqualTo("report.pdf");
        assertThat(AttachmentController.cleanFilename("../../etc/pass\"wd\n")).isEqualTo("passwd");
        assertThat(AttachmentController.cleanFilename(null)).isEqualTo("file");
        assertThat(AttachmentController.cleanFilename("   ")).isEqualTo("file");
    }
}

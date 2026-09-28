// SPDX-License-Identifier: GPL-3.0-or-later — Catalyst, see LICENSE
package gg.catalyst.upload;

import org.junit.jupiter.api.Test;

import java.util.List;

import static gg.catalyst.upload.ReportUploader.extractUrls;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ReportUploaderUrlTest {
    private static final String REPLY = """
            {"success":true,
             "result":{"urls":["https://www.example.com/f/abc.txt"]},
             "docs":"https://www.example.com/api-docs"}""";

    @Test
    void escapedSlashesComeBackUnchanged() {
        final String escaped = "{\"error\":false,\"urls\":[\"https:\\/\\/files.example.com\\/files\\/a.txt\"]}";
        assertEquals(List.of("https://files.example.com/files/a.txt"), extractUrls(escaped, "urls"));
    }

    @Test
    void baseAddressGluedOntoAnAbsoluteLinkIsRemoved() {
        final String reply = "{\"urls\":[\"https://www.example.com/https://files.example.com/files/a.txt\"]}";
        assertEquals(List.of("https://files.example.com/files/a.txt"), extractUrls(reply, "urls"));
    }

    @Test
    void linkInsideTheQueryStringIsLeftAlone() {
        final String url = "https://example.com/go?redirect=https://other.example.com/x";
        assertEquals(url, ReportUploader.unjoin(url));
    }

    @Test
    void configuredDottedPathReturnsOnlyThatUrl() {
        assertEquals(List.of("https://www.example.com/f/abc.txt"), extractUrls(REPLY, "result.urls"));
    }

    @Test
    void jsonPointerPathWorksToo() {
        assertEquals(List.of("https://www.example.com/f/abc.txt"), extractUrls(REPLY, "/result/urls"));
    }

    @Test
    void noPathReturnsEveryUrlInTheTree() {
        assertEquals(List.of("https://www.example.com/f/abc.txt", "https://www.example.com/api-docs"),
                extractUrls(REPLY, ""));
    }

    @Test
    void wrongPathFallsBackToTheWholeTree() {
        assertEquals(2, extractUrls(REPLY, "result.nothing.here").size());
    }

    @Test
    void plainTextReplyIsScannedAndPunctuationTrimmed() {
        assertEquals(List.of("https://www.example.com/f/abc.txt"),
                extractUrls("Uploaded! Your file is at https://www.example.com/f/abc.txt.", ""));
    }

    @Test
    void urlHiddenInsideAnEscapedStringIsStillFound() {
        final String reply = "{\"html\":\"<a href=\\\"https://www.example.com/f/x\\\">x</a>\"}";

        // The tree walk sees only the whole string, which does not start with http,
        // so the regex pass has to find it.
        assertEquals(List.of("https://www.example.com/f/x"), extractUrls(reply, ""));
    }

    @Test
    void nothingToFindGivesAnEmptyList() {
        assertEquals(List.of(), extractUrls("{\"success\":false,\"error\":\"expired\"}", ""));
    }
}

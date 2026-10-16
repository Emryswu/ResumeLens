package com.arthur.jdragresume.dto.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReportItemsTests {
    @Test
    void roundTripsWithEnglishCategoryCodes() {
        List<ReportItem> items = List.of(new ReportItem("Java", "你做过 Java 服务。", List.of(0, 2),
                ReportCategory.TECH_STACK, ReportItem.Source.MODEL));

        String json = ReportItems.write(items);

        assertEquals("[{\"title\":\"Java\",\"detail\":\"你做过 Java 服务。\",\"chunks\":[0,2],"
                + "\"category\":\"TECH_STACK\",\"source\":\"MODEL\"}]", json);
        assertEquals(items, ReportItems.read(json));
    }

    @Test
    void oldOrUnreadableReportsReadAsNullSoTheClientFallsBackToText() {
        assertNull(ReportItems.read(null));
        assertNull(ReportItems.read(""));
        assertNull(ReportItems.read("not json"));
        assertNull(ReportItems.read("[{\"title\":\"x\",\"category\":\"技术栈\"}]"));
    }

    @Test
    void unknownCategoryCodesFallBackToGeneral() {
        assertEquals(ReportCategory.COLLABORATION, ReportCategory.fromCode(" collaboration "));
        assertEquals(ReportCategory.TECH_STACK, ReportCategory.fromCode("tech-stack"));
        assertEquals(ReportCategory.GENERAL, ReportCategory.fromCode("技术栈"));
        assertEquals(ReportCategory.GENERAL, ReportCategory.fromCode(null));
    }

    @Test
    void legacyLineKeepsTheCitationTokensOtherReadersExpect() {
        assertEquals("部署：你还没写部署经验。", new ReportItem("部署", "你还没写部署经验。", List.of(),
                null, null).legacyLine());
        assertEquals("Java [chunk-0] [chunk-1]", new ReportItem("Java", "", List.of(0, 1), null, null).legacyLine());
    }
}

package com.ch4nhm.tvapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class SearchSuggestionClientTest {

    @Test
    public void parsesBaiduJsonpSuggestions() {
        String body = "window.baidu.sug({q:\"frxxz\",p:false,"
                + "s:[\"凡人修仙传\",\"凡人修仙之玄骨传\"]});";

        assertEquals(List.of("凡人修仙传", "凡人修仙之玄骨传"),
                SearchSuggestionClient.parse(body));
    }

    @Test
    public void trimsAndDeduplicatesSuggestions() {
        String body = "window.baidu.sug({s:[\" 凡人修仙传 \","
                + "\"凡人·修仙传\",\"凡人修仙之玄骨传\",\"\"]});";

        assertEquals(List.of("凡人修仙传", "凡人修仙之玄骨传"),
                SearchSuggestionClient.parse(body));
    }

    @Test
    public void malformedPayloadFallsBackToEmptyList() {
        assertTrue(SearchSuggestionClient.parse("window.baidu.sug({s:not-json});").isEmpty());
        assertTrue(SearchSuggestionClient.parse("").isEmpty());
    }
}

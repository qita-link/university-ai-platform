package com.campus.platform.retrieval.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MysqlKeywordRetrievalServiceTest {

    @Test
    void splitsNaturalChineseQuestionIntoBigrams() {
        String query = MysqlKeywordRetrievalService.toBooleanQuery("报到需要带什么？");
        assertThat(query).contains("报到").doesNotContain("需要", "什么", "？");
    }

    @Test
    void stripsBooleanOperatorsAndKeepsAsciiWords() {
        assertThat(MysqlKeywordRetrievalService.toBooleanQuery("+奖学金 -食堂"))
                .contains("奖学", "学金", "食堂")
                .doesNotContain("+", "-");
        assertThat(MysqlKeywordRetrievalService.toBooleanQuery("jAccount 登录"))
                .contains("jAccount", "登录");
    }

    @Test
    void blankOrOperatorOnlyQueryBecomesEmpty() {
        assertThat(MysqlKeywordRetrievalService.toBooleanQuery("+++---")).isEmpty();
        assertThat(MysqlKeywordRetrievalService.toBooleanQuery("  ")).isEmpty();
        assertThat(MysqlKeywordRetrievalService.toBooleanQuery(null)).isEmpty();
    }
}

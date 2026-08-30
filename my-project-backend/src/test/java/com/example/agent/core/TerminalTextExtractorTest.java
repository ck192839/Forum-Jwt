package com.example.agent.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerminalTextExtractorTest {

    @Test
    void streamsAnswerTextIncrementallyAcrossChunks() {
        TerminalTextExtractor extractor = new TerminalTextExtractor();

        assertEquals("", extractor.append("{\"type\":\"ANS"));
        assertEquals("东镇大街", extractor.append("WER\",\"answer\":\"东镇大街"));
        assertEquals("的牛腩，", extractor.append("的牛腩，"));
        // 闭合引号后的 } 不属于正文
        assertEquals("好吃。", extractor.append("好吃。\"}"));
    }

    @Test
    void decodesEscapesWhileStreaming() {
        TerminalTextExtractor extractor = new TerminalTextExtractor();

        // \n 转义完整到达即解码
        assertEquals("第一行\n", extractor.append("{\"type\":\"ANSWER\",\"answer\":\"第一行\\n"));
        assertEquals("第二行\t制表 \\ 引号\"结束", extractor.append("第二行\\t制表 \\\\ 引号\\\"结束\"}"));
    }

    @Test
    void holdsBackIncompleteUnicodeEscapeUntilComplete() {
        TerminalTextExtractor extractor = new TerminalTextExtractor();
        StringBuilder streamed = new StringBuilder();
        streamed.append(extractor.append("{\"type\":\"ANSWER\",\"answer\":\"\\u597d"));
        // \uD83D\uDE00 = 😀，拆在两个 chunk 到达；拼接后的增量必须还原出完整字符
        streamed.append(extractor.append("\\uD83D"));
        streamed.append(extractor.append("\\uDE00\"}"));
        assertEquals("好😀", streamed.toString());
    }

    @Test
    void streamsQuestionAndDraftBodyFields() {
        TerminalTextExtractor question = new TerminalTextExtractor();
        assertEquals("适用哪个宿舍楼？", question.append(
                "{\"type\":\"QUESTION\",\"question\":\"适用哪个宿舍楼？\"}"));

        TerminalTextExtractor draft = new TerminalTextExtractor();
        assertEquals("## 步骤\\n重启客户端。".replace("\\n", "\n"), draft.append(
                "{\"type\":\"DRAFT\",\"title\":\"指南\",\"topicTypeId\":1,"
                        + "\"bodyMarkdown\":\"## 步骤\\n重启客户端。\",\"citations\":[],\"basedOnEditorVersion\":0}"));
    }

    @Test
    void ignoresKeyLookalikesInsideEarlierValues() {
        TerminalTextExtractor extractor = new TerminalTextExtractor();
        // title 的值里出现了 "answer":" 字样，不能被误认为正文开始
        assertEquals("", extractor.append("{\"type\":\"DRAFT\",\"title\":\"fake\",\"topicTypeId\":1,"));
        assertEquals("", extractor.append("\"body\":\"x\",\"answer\":\"in title\""));
        assertEquals("真正文", extractor.append(",\"bodyMarkdown\":\"真正文\"}"));
    }

    @Test
    void emitsNothingForUnknownOrMissingType() {
        TerminalTextExtractor extractor = new TerminalTextExtractor();
        assertEquals("", extractor.append("{\"foo\":\"bar\",\"answer\":\"x\"}"));
        assertEquals("", extractor.append("{\"type\":\"OTHER\",\"answer\":\"x\"}"));
        assertEquals("", extractor.append("not json at all"));
    }
}

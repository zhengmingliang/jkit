package com.alianga.jkit.http;

import com.alianga.jkit.HttpUtils;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public class SseMergeAndCurlTest {
    @Test
    public void mergeOpenAiAndClaudeAndOllama() {
        SseMerger openai = new SseMerger(SseMergeFormat.OPENAI);
        openai.acceptData("{\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}");
        openai.acceptData("{\"choices\":[{\"delta\":{\"reasoning_content\":\"think\"}}]}");
        openai.acceptData("{\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}");
        SseMergeResult done = openai.acceptData("[DONE]");
        assertEquals("Hello", openai.getContent());
        assertEquals("think", openai.getThinking());
        assertTrue(done.isDone());

        SseMerger claude = new SseMerger(SseMergeFormat.CLAUDE);
        claude.accept(new SseEvent(null, "content_block_delta",
                "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}", -1));
        claude.accept(new SseEvent(null, "content_block_delta",
                "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"!\"}}", -1));
        assertEquals("Hi!", claude.getContent());

        SseMerger ollama = new SseMerger(SseMergeFormat.OLLAMA_CHAT);
        ollama.acceptData("{\"message\":{\"content\":\"A\"},\"done\":false}");
        ollama.acceptData("{\"message\":{\"content\":\"B\"},\"done\":true}");
        assertEquals("AB", ollama.getContent());
        assertTrue(ollama.isDone());

        SseMerger gemini = new SseMerger(SseMergeFormat.GEMINI);
        gemini.acceptData("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Ge\"}]}}]}");
        gemini.acceptData("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"mini\"}]},\"finishReason\":\"STOP\"}]}");
        assertEquals("Gemini", gemini.getContent());
        assertTrue(gemini.isDone());

        SseMerger path = new SseMerger(SseMergeFormat.jsonPath("$.choices[0].delta.content"));
        path.acceptData("{\"choices\":[{\"delta\":{\"content\":\"X\"}}]}");
        assertEquals("X", path.getContent());
        assertEquals("/choices/0/delta/content", SseMerger.toNodePath("$.choices[0].delta.content"));
    }

    @Test
    public void mergeClaudeDoesNotEndOnContentBlockStop() {
        SseMerger claude = new SseMerger(SseMergeFormat.CLAUDE);
        claude.accept(new SseEvent(null, "content_block_delta",
                "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"plan\"}}",
                -1));
        claude.accept(new SseEvent(null, "content_block_stop",
                "{\"type\":\"content_block_stop\",\"index\":0}", -1));
        assertFalse(claude.isDone());
        claude.accept(new SseEvent(null, "content_block_delta",
                "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}",
                -1));
        claude.accept(new SseEvent(null, "content_block_delta",
                "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"a\\\"\"}}",
                -1));
        claude.accept(new SseEvent(null, "message_stop", "{\"type\":\"message_stop\"}", -1));
        assertEquals("Hi", claude.getContent());
        assertEquals("plan", claude.getThinking());
        assertEquals("{\"a\"", claude.getToolCall());
        assertTrue(claude.isDone());

        SseMerger error = new SseMerger(SseMergeFormat.CLAUDE);
        SseMergeResult failed = error.accept(new SseEvent(null, "error",
                "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"busy\"}}",
                -1));
        assertTrue(failed.isDone());
        assertEquals("busy", failed.getError());

        SseMerger quota = new SseMerger(SseMergeFormat.CLAUDE);
        SseMergeResult quotaResult = quota.accept(new SseEvent(null, "message",
                "{\"error\":{\"code\":\"AccountQuotaExceeded\",\"message\":\"You have exceeded the quota\","
                        + "\"param\":\"\",\"type\":\"TooManyRequests\"}}",
                -1));
        assertTrue(quotaResult.isDone());
        assertEquals("You have exceeded the quota", quotaResult.getError());
        assertEquals("TooManyRequests", quotaResult.getFinishReason());
        assertEquals("You have exceeded the quota", quota.snapshot().getError());

        SseMerger openaiError = new SseMerger(SseMergeFormat.OPENAI);
        SseMergeResult openaiFailed = openaiError.acceptData(
                "{\"error\":{\"message\":\"invalid api key\",\"type\":\"invalid_request_error\"}}");
        assertEquals("invalid api key", openaiFailed.getError());
        assertTrue(openaiFailed.isDone());
    }

    @Test
    public void mergeOpenAiReasoningToolCallAndResponsesApi() {
        SseMerger openai = new SseMerger(SseMergeFormat.OPENAI);
        openai.acceptData("{\"choices\":[{\"delta\":{\"reasoning\":\"step1\"},\"finish_reason\":\"\"}]}");
        assertFalse(openai.isDone());
        openai.acceptData("{\"choices\":[{\"delta\":{\"reasoning_details\":[{\"type\":\"reasoning.text\",\"text\":\"step2\"}]}}]}");
        openai.acceptData("{\"choices\":[{\"delta\":{\"content\":\"9.11\"}}]}");
        openai.acceptData("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"function\":{\"arguments\":\"{\\\"x\\\"\"}}]}}]}");
        openai.acceptData("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"function\":{\"arguments\":\":1}\"}}]}}]}");
        SseMergeResult last = openai.acceptData(
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}");
        assertEquals("9.11", openai.getContent());
        assertEquals("step1step2", openai.getThinking());
        assertEquals("{\"x\":1}", openai.getToolCall());
        assertTrue(last.isDone());
        assertEquals("stop", last.getFinishReason());

        SseMerger responses = new SseMerger(SseMergeFormat.OPENAI_RESPONSES);
        responses.acceptData("{\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\"}}");
        responses.acceptData("{\"type\":\"response.reasoning_text.delta\",\"delta\":\"hmm\"}");
        responses.acceptData("{\"type\":\"response.output_text.delta\",\"delta\":\"In\"}");
        responses.acceptData("{\"type\":\"response.output_text.delta\",\"delta\":\" a forest\"}");
        responses.acceptData("{\"type\":\"response.output_text.done\",\"text\":\"In a forest\"}");
        SseMergeResult completed = responses.acceptData(
                "{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}");
        assertEquals("In a forest", responses.getContent());
        assertEquals("hmm", responses.getThinking());
        assertTrue(completed.isDone());
        assertEquals("completed", completed.getFinishReason());
    }

    @Test
    public void mergeDashScopeErnieGeminiThoughtAndAutoDetect() {
        SseMerger incremental = new SseMerger(SseMergeFormat.DASHSCOPE);
        incremental.acceptData("{\"output\":{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"我是\"},\"finish_reason\":null}]}}");
        incremental.acceptData("{\"output\":{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"通义\"},\"finish_reason\":null}]}}");
        incremental.acceptData("{\"output\":{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"。\",\"reasoning_content\":\"想一下\"},\"finish_reason\":\"stop\"}]}}");
        assertEquals("我是通义。", incremental.getContent());
        assertEquals("想一下", incremental.getThinking());
        assertTrue(incremental.isDone());

        SseMerger cumulative = new SseMerger(SseMergeFormat.DASHSCOPE);
        cumulative.acceptData("{\"output\":{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"我是\"},\"finish_reason\":null}]}}");
        cumulative.acceptData("{\"output\":{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"我是通义千问\"},\"finish_reason\":null}]}}");
        cumulative.acceptData("{\"output\":{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"我是通义千问。\"},\"finish_reason\":\"stop\"}]}}");
        assertEquals("我是通义千问。", cumulative.getContent());
        assertTrue(cumulative.isDone());

        SseMerger ernie = new SseMerger(SseMergeFormat.ERNIE);
        ernie.acceptData("{\"result\":\"你好\",\"is_end\":false}");
        SseMergeResult end = ernie.acceptData("{\"result\":\"，我是文心一言。\",\"is_end\":true}");
        assertEquals("你好，我是文心一言。", ernie.getContent());
        assertTrue(end.isDone());

        SseMerger gemini = new SseMerger(SseMergeFormat.GEMINI);
        gemini.acceptData("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"plan\",\"thought\":true}]}}]}");
        gemini.acceptData("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}]}");
        assertEquals("ok", gemini.getContent());
        assertEquals("plan", gemini.getThinking());
        assertTrue(gemini.isDone());

        SseMerger auto = new SseMerger(SseMergeFormat.AUTO);
        auto.accept(new SseEvent(null, "content_block_delta",
                "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Auto\"}}",
                -1));
        auto.accept(new SseEvent(null, "message_stop", "{\"type\":\"message_stop\"}", -1));
        assertEquals("Auto", auto.getContent());
        assertTrue(auto.isDone());

        SseMerger autoOpenAi = new SseMerger(SseMergeFormat.AUTO);
        autoOpenAi.acceptData("{\"choices\":[{\"delta\":{\"content\":\"A\"}}]}");
        autoOpenAi.acceptData("[DONE]");
        assertEquals("A", autoOpenAi.getContent());
        assertTrue(autoOpenAi.isDone());

        SseMerger autoDash = new SseMerger(SseMergeFormat.AUTO);
        autoDash.acceptData("{\"output\":{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Q\"},\"finish_reason\":\"stop\"}]}}");
        assertEquals("Q", autoDash.getContent());
        assertTrue(autoDash.isDone());
    }

    @Test
    public void mergeSenseNovaChatCompletionsChunks() {
        SseMerger openai = new SseMerger(SseMergeFormat.OPENAI);
        openai.acceptData("{\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning\":\"Thinking\"},\"finish_reason\":\"\"}]}");
        assertFalse(openai.isDone());
        assertEquals("", openai.getContent());
        assertEquals("Thinking", openai.getThinking());
        openai.acceptData("{\"choices\":[{\"delta\":{\"reasoning\":\" step\"},\"finish_reason\":\"\"}]}");
        openai.acceptData("{\"choices\":[{\"delta\":{\"content\":\"Hello\"},\"finish_reason\":\"\"}]}");
        openai.acceptData("{\"choices\":[{\"delta\":{\"content\":\"!\"},\"finish_reason\":\"\"}]}");
        openai.acceptData("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}");
        openai.acceptData("{\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":8}}");
        SseMergeResult done = openai.acceptData("[DONE]");
        assertEquals("Hello!", openai.getContent());
        assertEquals("Thinking step", openai.getThinking());
        assertTrue(done.isDone());
        assertEquals("stop", openai.getFinishReason());

        SseMerger auto = new SseMerger(SseMergeFormat.AUTO);
        auto.acceptData("{\"choices\":[{\"delta\":{\"reasoning\":\"plan\"},\"finish_reason\":\"\"}]}");
        auto.acceptData("{\"choices\":[{\"delta\":{\"content\":\"OK\"},\"finish_reason\":\"\"}]}");
        auto.acceptData("[DONE]");
        assertEquals("OK", auto.getContent());
        assertEquals("plan", auto.getThinking());
        assertTrue(auto.isDone());

        SseMerger wrapped = new SseMerger(SseMergeFormat.OPENAI);
        wrapped.acceptData("{\"data\":{\"choices\":[{\"delta\":\"\",\"reasoning_content\":\"hmm\",\"finish_reason\":\"\"}]},\"status\":{\"code\":0}}");
        wrapped.acceptData("{\"data\":{\"choices\":[{\"delta\":\"Hi\",\"reasoning_content\":\"\",\"finish_reason\":\"\"}]},\"status\":{\"code\":0}}");
        assertEquals("Hi", wrapped.getContent());
        assertEquals("hmm", wrapped.getThinking());
    }

    @Test
    public void parseCurlToRequest() {
        String curl = "curl -X POST 'https://example.com/v1/chat' \\\n"
                + "  -H 'Authorization: Bearer tok' \\\n"
                + "  -H 'Content-Type: application/json' \\\n"
                + "  --data-raw '{\"model\":\"gpt-4\"}'";
        CurlRequest parsed = CurlParser.parse(curl);
        assertEquals("POST", parsed.getMethod());
        assertEquals("https://example.com/v1/chat", parsed.getUrl());
        assertEquals("Bearer tok", parsed.getHeaders().get("Authorization"));
        assertEquals("application/json", parsed.getHeaders().get("Content-Type"));
        assertEquals("{\"model\":\"gpt-4\"}", parsed.getBody());
        HttpRequest request = parsed.toHttpRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("https://example.com/v1/chat", request.getUrl());
        assertEquals("Bearer tok", request.getHeader("Authorization"));
    }

    @Test
    public void requestToCurlRoundTripPreservesComplexRequest() {
        HttpRequest request = HttpRequest.post("https://example.com/api?q=1")
                .header("X-Trace", "a'b")
                .header("Content-Type", "application/json")
                .body("{\"message\":\"hello 'world'\"}", StandardCharsets.UTF_8)
                .ignoreSsl(false)
                .followRedirects(true)
                .proxy(new java.net.Proxy(java.net.Proxy.Type.HTTP,
                        new InetSocketAddress("127.0.0.1", 8080)));
        String curl = HttpUtils.requestToCurl(request);
        assertTrue(curl.startsWith("curl -X 'POST'"));
        assertTrue(curl.contains("X-Trace: a'\\''b"));
        assertTrue(curl.contains("--data-binary '{\"message\":\"hello '\\''world'\\''\"}'"));
        assertTrue(curl.contains("127.0.0.1:8080"));
        assertFalse(curl.contains(" -k"));
        CurlRequest parsed = CurlParser.parse(curl);
        assertEquals("POST", parsed.getMethod());
        assertEquals("https://example.com/api?q=1", parsed.getUrl());
        assertEquals("application/json", parsed.getContentType());
        assertEquals("{\"message\":\"hello 'world'\"}", parsed.getBody());
    }

    @Test
    public void requestToCurlSupportsGetBodyFileAndQueryParameters() {
        HttpRequest get = HttpRequest.get("https://example.com/search")
                .query("q", "hello world")
                .query("ids", java.util.Arrays.asList(1, 2));
        String getCurl = HttpUtils.requestToCurl(get);
        CurlRequest parsedGet = CurlParser.parse(getCurl);
        assertEquals("https://example.com/search?q=hello+world&ids=1&ids=2", parsedGet.getUrl());

        HttpRequest file = HttpRequest.put("https://example.com/upload")
                .bodyFile(new java.io.File("target/data.bin"));
        String fileCurl = HttpUtils.requestToCurl(file);
        assertTrue(fileCurl.contains("--data-binary '@target/data.bin'"));
        assertTrue(fileCurl.contains("target/data.bin"));
    }

    @Test
    public void parseComplexOptionsAndLastHeaderWins() {
        CurlRequest request = CurlParser.parse(
                "curl --request PATCH --url='https://example.com/a b' "
                        + "-H 'X-Test: first' -H 'X-Test: second' "
                        + "--data-raw 'a=1' --data 'b=2' --compressed --silent "
                        + "--connect-timeout 3 --max-time=10");
        assertEquals("PATCH", request.getMethod());
        assertEquals("https://example.com/a b", request.getUrl());
        assertEquals("second", request.getHeaders().get("X-Test"));
        assertEquals("a=1&b=2", request.getBody());
        assertEquals("application/x-www-form-urlencoded", request.getContentType());
    }

    @Test
    public void parseCurlGetAndBasicAuth() {
        CurlRequest parsed = HttpUtils.parseCurl("curl -G 'https://ex.com/q' -d 'a=1' -u user:pass -k");
        assertEquals("GET", parsed.getMethod());
        assertTrue(parsed.getUrl().contains("a=1"));
        assertTrue(parsed.isInsecure());
        HttpRequest request = parsed.toHttpRequest();
        assertTrue(request.getHeader("Authorization").startsWith("Basic "));
    }
}

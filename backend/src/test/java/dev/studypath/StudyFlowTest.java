package dev.studypath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:studyflow;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "study.model.key=", "study.model.deepseek-key=", "study.model.langchain-url="})
@AutoConfigureMockMvc
class StudyFlowTest {
    private static final String PROFILE = """
            {"countries":["UK"],"track":"ALL","year":2027,"major":"QUANTITATIVE","instruction":"OTHER",
             "math":true,"programming":true,"ieltsOverall":6.5,"ieltsBand":6.0}
            """;

    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired StudyStore store;
    @Autowired SourceAssistant assistant;
    @Autowired RuleEvaluator evaluator;

    @Test
    void sameLanguageScoreHasDifferentOutcomesAndReportCanBeReopened() throws Exception {
        var assessment = submit(PROFILE);
        assertThat(assessment.path("results").size()).isEqualTo(7);
        assertThat(findProgram(assessment, "ucl-cs").at("/checks/3/status").asText()).isEqualTo("GAP");
        assertThat(findProgram(assessment, "ucl-ds").at("/checks/3/status").asText()).isEqualTo("MATCH");

        String id = assessment.path("id").asText();
        var response = http.perform(get("/api/assessments/{id}", id)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(response)).isEqualTo(assessment);
    }

    @Test
    void changingCountryAndDirectionSelectsTheRightProgram() throws Exception {
        var assessment = submit(PROFILE.replace("\"UK\"", "\"SG\"").replace("\"ALL\"", "\"AI_DATA\""));
        assertThat(assessment.path("results").size()).isEqualTo(3);
        assertThat(findProgram(assessment, "nus-ai").at("/checks/1/status").asText()).isEqualTo("MATCH");
        assertThat(findProgram(assessment, "nus-dsml").at("/program/country").asText()).isEqualTo("SG");
    }

    @Test
    void missingScoreAndMissingIntakeStayUnknown() throws Exception {
        var incomplete = submit(PROFILE.replace("6.5", "null").replace("6.0", "null"));
        assertThat(findProgram(incomplete, "ucl-cs").at("/checks/3/status").asText()).isEqualTo("UNKNOWN");
        assertThat(submit(PROFILE.replace("2027", "2028")).path("results").size()).isZero();
    }

    @Test
    void tierSeparatesScoreGapsAndOnlyUsesTheConfirmedSnapshotForSafety() throws Exception {
        var baseline = submit(PROFILE);
        assertThat(findProgram(baseline, "ucl-cs").at("/recommendation/tier").asText()).isEqualTo("REACH");
        assertThat(findProgram(baseline, "ucl-ds").at("/recommendation/tier").asText()).isEqualTo("FIT");
        assertThat(findProgram(baseline, "leeds-advanced-cs").at("/recommendation/tier").asText()).isEqualTo("GAP");
        var stronger = PROFILE.replace("6.5", "7.0").replace("6.0", "6.5");
        assertThat(findProgram(submit(stronger), "ucl-ds").at("/recommendation/tier").asText()).isEqualTo("FIT");
        var request = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(stronger);
        request.putArray("confirmedProgramSnapshots").add("ucl-ds@2027/2026-11-01");
        assertThat(findProgram(submit(request.toString()), "ucl-ds").at("/recommendation/tier").asText()).isEqualTo("FIT");
        request.putArray("confirmedProgramSnapshots").add("ucl-ds@2027/2026-11-12");
        assertThat(findProgram(submit(request.toString()), "ucl-ds").at("/recommendation/tier").asText()).isEqualTo("SAFETY_CANDIDATE");
    }

    @Test
    void readsNewCountriesAndComparesOnlyTheCorrectGradeScale() throws Exception {
        var input = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(PROFILE);
        input.putArray("countries").add("US").add("AU");
        input.put("gradeScale", "LAST60_GPA4").put("grade", 2.85).put("ieltsOverall", 7.0).put("ieltsBand", 6.5);
        var report = submit(input.toString());
        var utd = findProgram(report, "utd-mscs");
        assertThat(utd.at("/academics/state").asText()).isEqualTo("GAP");
        assertThat(utd.at("/recommendation/tier").asText()).isEqualTo("REACH");
        assertThat(utd.at("/guide/materials").size()).isGreaterThan(3);
        assertThat(findProgram(report, "melbourne-ds").at("/academics/state").asText()).isEqualTo("SCALE_DIFFERS");

        input.put("gradeScale", "MELBOURNE_WAM").put("grade", 70);
        assertThat(findProgram(submit(input.toString()), "melbourne-ds").at("/academics/state").asText()).isEqualTo("MATCH");
        input.put("gradeScale", "GPA4");
        http.perform(post("/api/assessments").contentType(MediaType.APPLICATION_JSON).content(input.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void completeMatchingProfileCanGetADirectSafetyCandidate() throws Exception {
        var input = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(PROFILE);
        input.put("major", "COMPUTING").put("gradeScale", "UK_HONOURS").put("grade", 2);
        var report = submit(input.toString());
        assertThat(findProgram(report, "portsmouth-is").at("/recommendation/tier").asText()).isEqualTo("SAFETY_CANDIDATE");
        assertThat(findProgram(report, "ucl-cs").at("/recommendation/tier").asText()).isEqualTo("GAP");
    }

    @Test
    void planEndpointProvidesALocalFallbackWhenCloudIsUnconfigured() throws Exception {
        var id = submit(PROFILE).path("id").asText();
        http.perform(get("/api/model")).andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(false));
        http.perform(post("/api/assessments/{id}/plan", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.generated").value(false)).andExpect(jsonPath("$.advice").isNotEmpty());
    }

    @Test
    void langChainClientSendsScopedPromptToTheLocalService() throws Exception {
        var payload = new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        var authorization = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/generate", exchange -> {
            try (exchange) {
                payload.set(json.readTree(exchange.getRequestBody()));
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] body = json.writeValueAsBytes(json.createObjectNode().put("content", "{\"advice\":\"示例\"}"));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            var client = new LangChainClient("http://127.0.0.1:" + server.getAddress().getPort(), "local-test-token");
            var reply = client.chat(SystemMessage.from("输出 JSON"), UserMessage.from("官网条款与问题"));
            assertThat(json.readTree(reply.aiMessage().text()).path("advice").asText()).isEqualTo("示例");
            assertThat(payload.get().path("instructions").asText()).isEqualTo("输出 JSON");
            assertThat(payload.get().path("context").asText()).isEqualTo("官网条款与问题");
            assertThat(authorization.get()).isEqualTo("Bearer local-test-token");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retrievalUsesOnlyTheSelectedSnapshotAndRejectsUnselectedPrograms() throws Exception {
        var id = submit(PROFILE).path("id").asText();
        String question = "{\"question\":\"雅思学术类总分和每个单项最低要多少？\"}";
        for (String program : new String[]{"ucl-cs", "ucl-ds"}) {
            var response = http.perform(post("/api/assessments/{id}/programs/{program}/ask", id, program)
                    .contentType(MediaType.APPLICATION_JSON).content(question)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.mode").value("RETRIEVAL"))
                    .andExpect(jsonPath("$.sources[0].id").value(program + ":ielts"))
                    .andReturn().getResponse().getContentAsString();
            json.readTree(response).path("sources").forEach(source ->
                    assertThat(source.path("id").asText()).startsWith(program + ":"));
        }
        http.perform(post("/api/assessments/{id}/programs/nus-ai/ask", id)
                .contentType(MediaType.APPLICATION_JSON).content(question)).andExpect(status().isNotFound());
    }

    @Test
    void rejectsImpossibleScore() throws Exception {
        http.perform(post("/api/assessments").contentType(MediaType.APPLICATION_JSON)
                .content(PROFILE.replace("6.0", "7.0"))).andExpect(status().isBadRequest());
    }

    @Test
    void sameQuestionHasTwoDeterministicConclusionsAndUsesExplicitScores() throws Exception {
        String id = submit(PROFILE.replace("6.5", "7.5").replace("6.0", "7.0")).path("id").asText();
        String question = "我雅思 6.5，最低单项 6.0，够吗？";
        var computing = ask(id, "ucl-cs", question);
        var dataScience = ask(id, "ucl-ds", question);
        assertThat(computing.path("conclusion").asText()).contains("低于");
        assertThat(dataScience.path("conclusion").asText()).contains("达到");
        assertThat(dataScience.path("explanation").asText()).contains("总分 6.5", "最低单项 6");
        assertThat(computing.at("/sources/0/id").asText()).isEqualTo("ucl-cs:ielts");
        assertThat(dataScience.at("/sources/0/id").asText()).isEqualTo("ucl-ds:ielts");
        assertThat(computing.path("toVerify").toString()).contains("总分还差 0.5 分", "最低单项还差 0.5 分");
        assertThat(dataScience.path("toVerify").size()).isEqualTo(1);
    }

    @Test
    void unsupportedQuestionsAndOtherScopesDoNotReceiveCitations() throws Exception {
        String id = submit(PROFILE).path("id").asText();
        for (String question : List.of("这个项目的奖学金申请截止时间是什么？", "2026 年入学雅思要求是多少？",
                "Data Science MSc 的雅思要求是多少？", "请告诉我今天的天气")) {
            var result = ask(id, "ucl-cs", question);
            assertThat(result.path("mode").asText()).isEqualTo("NO_EVIDENCE");
            assertThat(result.path("sources").isEmpty()).isTrue();
            assertThat(result.path("conclusion").asText()).isEqualTo("需要核实");
        }
    }

    @Test
    void modelExplanationRequiresAnExactCitationFromThisSnapshot() throws Exception {
        var report = json.treeToValue(submit(PROFILE), StudyData.Assessment.class);
        var program = report.results().stream().map(StudyData.Match::program).filter(p -> p.id().equals("ucl-cs")).findFirst().orElseThrow();
        var reply = new AtomicReference<String>();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                assertThat(request.messages().toString()).contains("ucl-cs:ielts@2027/2026-11-12")
                        .doesNotContain("ucl-ds:ielts");
                return ChatResponse.builder().aiMessage(AiMessage.from(reply.get())).build();
            }
        };
        var service = new SourceAssistant(json, evaluator, new BgeSmallZhV15QuantizedEmbeddingModel(), model);
        var question = new AdmissionQuestion("我雅思 6.5，最低单项 6.0，够吗？");

        reply.set("{\"explanation\":\"Level 2 同时有总分和单项要求。\",\"citationIds\":[\"ucl-cs:ielts@2027/2026-11-12\"]}");
        var valid = service.ask(program, report.profile(), question, store.catalog());
        assertThat(valid.mode()).isEqualTo("RAG");
        assertThat(valid.conclusion()).contains("低于");
        assertThat(valid.explanation()).contains("AI 条款解读");

        for (String citation : List.of("ucl-ds:ielts@2027/2026-11-12", "ucl-cs:ielts@2026/2026-11-12",
                "ucl-cs:ielts@2027/2026-10-01", "ucl-cs:ielts")) {
            reply.set("{\"explanation\":\"旧结果\",\"citationIds\":[\"" + citation + "\"]}");
            var fallback = service.ask(program, report.profile(), question, store.catalog());
            assertThat(fallback.mode()).isEqualTo("MODEL_UNAVAILABLE");
            assertThat(fallback.conclusion()).contains("低于");
            assertThat(fallback.explanation()).doesNotContain("旧结果");
            assertThat(fallback.sources()).extracting(StudyData.Evidence::id).containsExactly("ucl-cs:ielts");
        }
    }

    @Test
    void deepSeekUsesJsonModeAndDoesNotSendSavedScores() throws Exception {
        var report = json.treeToValue(submit(PROFILE.replace("6.5", "8.5").replace("6.0", "7.5")), StudyData.Assessment.class);
        var program = report.results().stream().map(StudyData.Match::program).filter(p -> p.id().equals("ucl-cs")).findFirst().orElseThrow();
        var request = new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        var authorization = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try (exchange) {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                request.set(json.readTree(exchange.getRequestBody()));
                var response = json.createObjectNode().put("id", "test-completion").put("object", "chat.completion")
                        .put("created", 1).put("model", "deepseek-flash");
                response.putArray("choices").addObject().put("index", 0).put("finish_reason", "stop")
                        .putObject("message").put("role", "assistant").put("content", """
                            {"explanation":"请查看官网英语要求。","citationIds":["ucl-cs:ielts@2027/2026-11-12"]}
                            """);
                byte[] bytes = json.writeValueAsBytes(response);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        try {
            var model = SourceAssistant.createModel("", "", "", "test-key", "http://127.0.0.1:" + server.getAddress().getPort());
            var service = new SourceAssistant(json, evaluator, new BgeSmallZhV15QuantizedEmbeddingModel(), model);
            var result = service.ask(program, report.profile(), new AdmissionQuestion("我填写的雅思成绩够吗？"), store.catalog());
            assertThat(result.mode()).isEqualTo("RAG");
            assertThat(result.conclusion()).contains("达到");
            assertThat(request.get().path("model").asText()).isEqualTo("deepseek-flash");
            assertThat(request.get().at("/response_format/type").asText()).isEqualTo("json_object");
            assertThat(request.get().at("/thinking/type").asText()).isEqualTo("disabled");
            assertThat(request.get().path("messages").toString()).doesNotContain("8.5", "7.5");
            assertThat(authorization.get()).isEqualTo("Bearer test-key");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void compareScopedAndUnscopedRetrievalOnLabeledExamples() {
        var programs = store.catalog();
        var embeddingModel = new BgeSmallZhV15QuantizedEmbeddingModel();
        var unscopedIndex = new InMemoryEmbeddingStore<TextSegment>();
        var segments = programs.stream().flatMap(program -> program.rules().stream().map(rule ->
                TextSegment.from(rule.title() + "。" + rule.summary(),
                        Metadata.from("evidenceId", rule.evidence(program).id())))).toList();
        unscopedIndex.addAll(embeddingModel.embedAll(segments).content(), segments);

        record Example(String program, String question, String evidenceId) {}
        var examples = List.of(
                new Example("ucl-cs", "雅思学术类总分和每个单项最低要多少？", "ucl-cs:ielts"),
                new Example("ucl-ds", "雅思学术类总分和每个单项最低要多少？", "ucl-ds:ielts"),
                new Example("nus-gen", "本科不是英语授课，雅思最低要几分？", "nus-gen:ielts"),
                new Example("nus-ai", "本科不是英语授课，雅思最低要几分？", "nus-ai:ielts"),
                new Example("ucl-cs", "本科软件工程是否在项目允许的本科专业范围？", "ucl-cs:major"),
                new Example("ucl-ds", "需要提供什么样的数学、概率和统计学习证明？", "ucl-ds:math")
        );
        int unscopedHits = 0;
        int scopedHits = 0;
        for (var example : examples) {
            var raw = unscopedIndex.search(EmbeddingSearchRequest.builder()
                    .queryEmbedding(embeddingModel.embed(example.question()).content()).maxResults(1).minScore(0.73).build());
            var rawId = raw.matches().stream().findFirst().map(match -> match.embedded().metadata().getString("evidenceId"));
            var program = programs.stream().filter(item -> item.id().equals(example.program())).findFirst().orElseThrow();
            var scopedId = assistant.retrieve(program, example.question()).stream().findFirst().map(StudyData.Evidence::id);
            if (rawId.filter(example.evidenceId()::equals).isPresent()) unscopedHits++;
            if (scopedId.filter(example.evidenceId()::equals).isPresent()) scopedHits++;
        }
        System.out.printf("Evidence Top-1 on %d curated examples: unscoped %d, scoped %d%n",
                examples.size(), unscopedHits, scopedHits);
        assertThat(scopedHits).isEqualTo(examples.size()).isGreaterThan(unscopedHits);
    }

    private com.fasterxml.jackson.databind.JsonNode submit(String profile) throws Exception {
        var response = http.perform(post("/api/assessments").contentType(MediaType.APPLICATION_JSON).content(profile))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private com.fasterxml.jackson.databind.JsonNode findProgram(com.fasterxml.jackson.databind.JsonNode assessment, String id) {
        for (var item : assessment.path("results")) {
            if (item.at("/program/id").asText().equals(id)) return item;
        }
        throw new AssertionError("Missing program: " + id);
    }

    private com.fasterxml.jackson.databind.JsonNode ask(String assessment, String program, String question) throws Exception {
        String response = http.perform(post("/api/assessments/{id}/programs/{program}/ask", assessment, program)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new StudyData.Question(question))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }
}

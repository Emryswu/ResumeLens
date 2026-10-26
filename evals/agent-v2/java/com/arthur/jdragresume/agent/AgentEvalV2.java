package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.tool.*;
import com.arthur.jdragresume.collect.BrowserExtensionJobCollector;
import com.arthur.jdragresume.collect.CollectRequest;
import com.arthur.jdragresume.collect.CollectionResult;
import com.arthur.jdragresume.collect.CollectionSession;
import com.arthur.jdragresume.collect.JobCollectionHistory;
import com.arthur.jdragresume.collect.JobCollector;
import com.arthur.jdragresume.collect.RoutingJobCollector;
import com.arthur.jdragresume.common.PageResponse;
import com.arthur.jdragresume.dto.job.JobCaptureRequest;
import com.arthur.jdragresume.dto.job.JobCaptureResponse;
import com.arthur.jdragresume.dto.job.JobDescriptionResponse;
import com.arthur.jdragresume.dto.job.ListingCaptureResult;
import com.arthur.jdragresume.dto.resume.ResumeResponse;
import com.arthur.jdragresume.dto.user.JobSearchPreferencesView;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.exception.ResourceNotFoundException;
import com.arthur.jdragresume.service.JobApplicationService;
import com.arthur.jdragresume.service.JobDescriptionService;
import com.arthur.jdragresume.service.JobSearchPreferencesService;
import com.arthur.jdragresume.service.ResumeService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test-only harness for evals/agent-v2. Resume reading, preferences, collection, detail verification,
 * JD reading, preference checks and recommendation acceptance run through the production tools; only
 * their storage and the browser are replaced by in-memory fakes that replay the frozen snapshots.
 * Ranking is the one stand-in tool: its similarity comes from each listing row's evalSimilarity.
 * Nothing here opens a browser, a database or BOSS.
 */
final class AgentEvalV2 {
    static final Path ROOT = Path.of("..", "evals", "agent-v2");
    static final ObjectMapper JSON = JsonMapper.builder().findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    static final List<String> GROUPS = List.of("dev", "acceptance");
    static final List<String> FAILURE_CATEGORIES = List.of(
            "STEP_LIMIT", "RULE_VIOLATION", "SNAPSHOT_MISSING", "PLAN_PROTOCOL", "OTHER");
    /** Every group must cover each of these rules with at least one task. */
    static final List<String> RULES = List.of("prerequisites", "inherit_filters",
            "stop_on_signal:BOSS_NOT_LOGGED_IN", "stop_on_signal:NO_MORE_RESULTS", "no_listing_as_full",
            "submit_accepted", "replan_offtarget", "gate_excluded:education", "preferred_accepted:education",
            "gate_excluded:experience", "preferred_accepted:experience");
    /** Listing rows ranked below this are off target for the resume. */
    static final double ON_TARGET = 0.5;
    static final int DAILY_PAGES = 30;
    static final String GENERIC_LISTING = "generic-unrecorded";
    private static final long USER_ID = 1L;
    private static final Pattern SNAPSHOT_ID = Pattern.compile("[a-z0-9-]{1,80}");
    private static final Pattern KEYWORD_SUFFIX = Pattern.compile("(实习生|实习|岗位)$");

    private AgentEvalV2() { }

    record Group(String name, JsonNode profile, JsonNode keywordSets, List<JsonNode> tasks) {
        JsonNode task(String id) {
            return tasks.stream().filter(task -> id.equals(task.path("id").asText())).findFirst().orElseThrow();
        }
    }

    record Check(String name, boolean passed, String reason) { }

    record Trial(boolean passed, List<Check> checks, String category, List<AgentLoop.State> states,
                 List<String> errorCodes, List<AgentMessage> transcript, List<String> trace) { }

    record Invocation(String callId, String name, JsonNode args, JsonNode result, int index, int turn) { }

    /** Effective command the replay collector received, after inherited filters were applied; not raw model JSON. */
    record ReplayCommand(String callId, int turn, String city, String experience, String keyword) { }

    /** Runtime facts about the replayed job library the graders may use. */
    record Library(Map<String, Long> ids, Map<Long, Double> similarity) {
        static Library empty() { return new Library(Map.of(), Map.of()); }
    }

    static JsonNode read(String relative) throws IOException {
        try (InputStream in = Files.newInputStream(ROOT.resolve(relative))) {
            return JSON.readTree(in);
        }
    }

    static Group group(String name) throws IOException {
        JsonNode file = read("tasks/" + name + ".json");
        if (!name.equals(file.path("group").asText())) throw new IllegalStateException("tasks/" + name + ".json: wrong group");
        List<JsonNode> tasks = new ArrayList<>();
        file.path("tasks").forEach(tasks::add);
        return new Group(name, read("snapshots/" + name + "/profile.json"), file.path("keywordSets"), List.copyOf(tasks));
    }

    /** The coverage label of one check, e.g. {@code gate_excluded:education}. */
    static String rule(JsonNode check) {
        String type = check.path("type").asText();
        return switch (type) {
            case "stop_on_signal" -> type + ":" + check.path("signal").asText();
            case "gate_excluded", "preferred_accepted" -> type + ":" + check.path("rule").asText();
            default -> type;
        };
    }

    static String normalizeKeyword(String keyword) {
        String value = Objects.requireNonNullElse(keyword, "").toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        for (String stripped = KEYWORD_SUFFIX.matcher(value).replaceAll(""); !stripped.equals(value);
             stripped = KEYWORD_SUFFIX.matcher(value).replaceAll("")) {
            value = stripped;
        }
        return value;
    }

    /** SHA-256 over every acceptance input, independent of line endings, so any edit is visible in review. */
    static String acceptanceDigest() throws Exception {
        List<Path> files = new ArrayList<>(List.of(ROOT.resolve("tasks/acceptance.json")));
        try (Stream<Path> snapshots = Files.walk(ROOT.resolve("snapshots/acceptance"))) {
            snapshots.filter(Files::isRegularFile).forEach(files::add);
        }
        files.sort(Comparator.comparing(path -> ROOT.relativize(path).toString().replace('\\', '/')));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Path file : files) {
            digest.update((ROOT.relativize(file).toString().replace('\\', '/') + "\n").getBytes(StandardCharsets.UTF_8));
            digest.update(Files.readString(file).replace("\r", "").getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    // ---------------------------------------------------------------- running a trial

    static Trial run(Group group, JsonNode task, Function<Scenario, AgentModel> models) throws IOException {
        Scenario scenario = new Scenario(group, task);
        AgentModel model = models.apply(scenario);
        AppUser user = new AppUser();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        ConversationStore store = mock(ConversationStore.class);
        AtomicReference<JobSearchContext> search = new AtomicReference<>(JobSearchContext.empty());
        when(store.searchContext(any())).thenAnswer(inv -> search.get());
        when(store.updateSearchContext(any(), any())).thenAnswer(inv -> {
            UnaryOperator<JobSearchContext> change = inv.getArgument(1);
            return search.updateAndGet(change);
        });
        AgentProperties props = new AgentProperties();
        props.setTurnTimeoutSeconds(120);
        TranscriptPolicy policy = new TranscriptPolicy(props);
        AgentLoop loop = new AgentLoop(model, scenario.registry(store), JSON, props, System::currentTimeMillis,
                new BrowserTaskRegistry(), store);
        List<AgentMessage> messages = new ArrayList<>();
        List<String> trace = new ArrayList<>();
        List<AgentLoop.State> states = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        String exceptionCode = null;
        JsonNode turns = task.path("turns");
        for (int turn = 0; turn < turns.size(); turn++) {
            scenario.turn = turn;
            messages.add(AgentMessage.user(turns.get(turn).asText()));
            var run = new ConversationStore.Run(USER_ID, 1L, "turn-" + turn, "run-" + turn,
                    policy.normalize(messages, (TranscriptPolicy.Approval) null), null, System.currentTimeMillis());
            var context = new AgentToolContext(user, run);
            try {
                RecordingSink sink = new RecordingSink();
                AgentLoop.Outcome output = loop.run(policy.normalize(messages, (TranscriptPolicy.Approval) null),
                        null, context, sink, () -> false);
                messages = new ArrayList<>(output.transcript());
                AgentLoop.State state = output.state();
                sink.drainInto(trace, errors);
                for (int browserRound = 0; state == AgentLoop.State.AWAITING_BROWSER; browserRound++) {
                    if (browserRound > 12) throw new IllegalStateException("browser replay did not finish");
                    AgentLoop.BrowserTaskEvent event = sink.browser();
                    JsonNode report = scenario.report(event, trace);
                    var pending = policy.normalize(messages, event.toolCallId(),
                            call -> ToolEnvelope.error(call.name(), "EVAL_ABANDONED", "replaced by browser result"));
                    var resume = new TranscriptPolicy.BrowserReport(event.toolCallId(), event.taskId(),
                            TranscriptPolicy.BrowserReport.COMPLETED, report);
                    sink = new RecordingSink();
                    output = loop.run(pending, null, context, sink, () -> false, System.currentTimeMillis(),
                            new AgentLoop.TurnOptions(null, List.of(), true, resume));
                    messages = new ArrayList<>(output.transcript());
                    state = output.state();
                    sink.drainInto(trace, errors);
                }
                states.add(state);
            } catch (Exception ex) {
                exceptionCode = ex instanceof BusinessException business ? business.getCode() : ex.getClass().getSimpleName();
                trace.add("evaluation exception: " + exceptionCode + ": " + ex.getMessage());
                states.add(AgentLoop.State.FAILED);
                break;
            }
        }
        List<Check> checks = grade(task, messages, scenario.replayed, scenario.library());
        String category = category(checks, messages, states, errors, exceptionCode);
        return new Trial("PASS".equals(category), checks, category, List.copyOf(states), List.copyOf(errors),
                List.copyOf(messages), List.copyOf(trace));
    }

    /**
     * A trial passes only when every turn completed and every check passed. Otherwise the first matching
     * category wins: a missing snapshot, the step budget, a plan protocol error that ended a turn
     * (code {@code AGENT_PLAN_*}), any other loop failure or harness exception, and finally rule violations.
     */
    static String category(List<Check> checks, List<AgentMessage> transcript, List<AgentLoop.State> states,
                           List<String> errors, String exception) {
        if ("EVAL_SNAPSHOT_MISSING".equals(exception) || transcript.stream().anyMatch(m -> m.content() != null
                && m.content().contains("EVAL_SNAPSHOT_MISSING"))) return "SNAPSHOT_MISSING";
        if (states.contains(AgentLoop.State.STEP_LIMIT) || errors.contains("AGENT_STEP_LIMIT")
                || transcript.stream().anyMatch(m -> m.content() != null && m.content().contains("AGENT_STEP_LIMIT")))
            return "STEP_LIMIT";
        if (Stream.concat(errors.stream(), Stream.ofNullable(exception)).anyMatch(code -> code.startsWith("AGENT_PLAN_")))
            return "PLAN_PROTOCOL";
        if (exception != null || states.stream().anyMatch(state -> state == AgentLoop.State.TIMED_OUT
                || state == AgentLoop.State.FAILED || state == AgentLoop.State.CANCELLED)) return "OTHER";
        if (checks.stream().anyMatch(check -> !check.passed())
                || states.isEmpty() || states.stream().anyMatch(state -> state != AgentLoop.State.COMPLETED))
            return "RULE_VIOLATION";
        return "PASS";
    }

    // ---------------------------------------------------------------- grading

    static List<Invocation> invocations(List<AgentMessage> messages) {
        Map<String, JsonNode> results = new HashMap<>();
        for (AgentMessage message : messages) if (AgentMessage.TOOL.equals(message.role())) {
            try { results.put(message.toolCallId(), JSON.readTree(message.content())); }
            catch (IOException ex) { throw new IllegalStateException("invalid tool JSON", ex); }
        }
        List<Invocation> calls = new ArrayList<>();
        int turn = -1;
        for (int index = 0; index < messages.size(); index++) {
            AgentMessage message = messages.get(index);
            if (AgentMessage.USER.equals(message.role())) turn++;
            if (!message.hasToolCalls()) continue;
            for (AgentMessage.ToolCall call : message.toolCalls()) {
                try {
                    calls.add(new Invocation(call.id(), call.name(), JSON.readTree(call.arguments()),
                            results.get(call.id()), index, turn));
                } catch (IOException ex) {
                    throw new IllegalStateException("invalid tool arguments", ex);
                }
            }
        }
        return calls;
    }

    static List<Check> grade(JsonNode task, List<AgentMessage> transcript, List<ReplayCommand> replayed, Library library) {
        List<Invocation> calls = invocations(transcript);
        Set<Long> verified = new HashSet<>();
        for (Invocation call : calls) if (call.name().equals("verify_boss_job") && ok(call.result())
                && "FULL".equals(data(call).path("captureDepth").asText())) {
            verified.add(data(call).path("jobId").asLong());
        }
        Set<Long> accepted = new HashSet<>();
        for (Invocation call : calls) if (call.name().equals("submit_recommendations") && ok(call.result())) {
            data(call).path("recommendations").forEach(item -> accepted.add(item.path("jobId").asLong()));
        }
        List<Invocation> collects = calls.stream().filter(c -> c.name().equals("collect_boss_jobs")).toList();
        List<Check> verdicts = new ArrayList<>();
        for (JsonNode check : task.path("checks")) {
            String type = check.path("type").asText();
            boolean passed;
            String failure;
            switch (type) {
                case "prerequisites" -> {
                    Invocation first = collects.isEmpty() ? null : collects.getFirst();
                    passed = first != null && calls.stream().anyMatch(c -> c.name().equals("get_resume")
                            && c.index() < first.index() && ok(c.result()) && data(c).hasNonNull("text"))
                            && calls.stream().anyMatch(c -> c.name().equals("get_job_search_preferences")
                            && c.index() < first.index() && ok(c.result()));
                    failure = "第一次采集前没有成功读取简历正文和求职条件";
                }
                case "inherit_filters" -> {
                    String city = check.path("city").asText();
                    String experience = check.path("experience").asText();
                    // Only collector arrivals count: refused calls (JOB_SEARCH_REPEAT etc.) never searched anything.
                    passed = replayed.stream().anyMatch(command -> command.turn() == 0)
                            && replayed.stream().anyMatch(command -> command.turn() > 0)
                            && replayed.stream().allMatch(command -> city.equals(command.city())
                                    && experience.equals(command.experience()));
                    failure = "不是每次到达采集器的搜索都保持 city=" + city + "、experience=" + experience
                            + "，或缺少首轮/后续轮的实际搜索";
                }
                case "stop_on_signal" -> {
                    String signal = check.path("signal").asText();
                    Invocation stopped = collects.stream().filter(c -> stopSignal(c.result(), signal)).findFirst().orElse(null);
                    passed = stopped != null && collects.stream().noneMatch(c ->
                            c.turn() == stopped.turn() && c.index() > stopped.index());
                    failure = stopped == null ? "没有观察到 " + signal + " 停止信号" : "遇到 " + signal + " 后同一轮仍再次采集";
                }
                case "no_listing_as_full" -> {
                    passed = true;
                    for (Invocation call : calls) if (call.name().equals("submit_recommendations")) {
                        // Proposing a LISTING-only job is the violation, even when the server rejects it.
                        for (JsonNode proposal : call.args().path("recommendations")) {
                            if (!verified.contains(proposal.path("jobId").asLong())) passed = false;
                        }
                    }
                    failure = "提交了未经 verify_boss_job 核实完整 JD 的岗位（即使服务端已拒绝）";
                }
                case "submit_accepted" -> {
                    passed = !accepted.isEmpty();
                    failure = "没有经 submit_recommendations 验收通过至少一个岗位";
                }
                case "replan_offtarget" -> {
                    Invocation first = collects.stream().filter(c -> arrived(c, replayed)).findFirst().orElse(null);
                    Invocation second = first == null ? null : collects.stream()
                            .filter(c -> c.index() > first.index() && arrived(c, replayed) && ok(c.result()))
                            .findFirst().orElse(null);
                    boolean offTarget = first != null && ok(first.result()) && !data(first).path("jobIds").isEmpty();
                    if (offTarget) for (JsonNode id : data(first).path("jobIds")) {
                        if (library.similarity().getOrDefault(id.asLong(), 0.0) >= ON_TARGET) offTarget = false;
                    }
                    boolean rankedBetween = second != null && calls.stream().anyMatch(c ->
                            c.name().equals("rank_jobs_for_resume") && ok(c.result())
                                    && c.index() > first.index() && c.index() < second.index());
                    boolean newKeyword = second != null && !normalizeKeyword(command(first, replayed).keyword())
                            .equals(normalizeKeyword(command(second, replayed).keyword()));
                    boolean withinBudget = collects.stream().noneMatch(c -> c.result() != null
                            && JobSearchWorkflow.BUDGET_ERROR.equals(c.result().path("error").path("code").asText()));
                    passed = offTarget && rankedBetween && newKeyword && withinBudget;
                    failure = !offTarget ? "首轮实际采集结果不是全部不对口，任务前提不成立"
                            : "首轮不对口后没有先排序、再换关键词成功补搜（或触发了采集预算拒绝）";
                }
                case "gate_excluded" -> {
                    Long id = library.ids().get(check.path("job").asText());
                    boolean inspected = id != null && verified.contains(id);
                    passed = inspected && !accepted.contains(id);
                    failure = !inspected ? "没有核实门槛岗位的完整 JD，无法证明是依据门槛排除"
                            : "不满足" + label(check) + "硬门槛的岗位通过了正式验收";
                }
                case "preferred_accepted" -> {
                    Long id = library.ids().get(check.path("job").asText());
                    passed = id != null && verified.contains(id) && accepted.contains(id);
                    failure = label(check) + "只是「优先」的岗位没有核实后通过正式验收";
                }
                default -> throw new IllegalStateException("unknown check " + type);
            }
            verdicts.add(new Check(rule(check), passed, passed ? "" : failure));
        }
        return verdicts;
    }

    private static String label(JsonNode check) {
        return "education".equals(check.path("rule").asText()) ? "学历" : "经验";
    }

    private static boolean stopSignal(JsonNode result, String signal) {
        if (result == null) return false;
        JsonNode data = result.path("untrusted_data");
        if ("NO_MORE_RESULTS".equals(signal)) {
            return ok(result) && signal.equals(data.path("finishReason").asText()) && data.path("pagesRead").asInt() == 1;
        }
        return signal.equals(result.path("error").path("code").asText()) || signal.equals(data.path("stopReason").asText());
    }

    private static boolean arrived(Invocation call, List<ReplayCommand> replayed) {
        return replayed.stream().anyMatch(command -> command.callId().equals(call.callId()));
    }

    private static ReplayCommand command(Invocation call, List<ReplayCommand> replayed) {
        return replayed.stream().filter(command -> command.callId().equals(call.callId())).findFirst().orElseThrow();
    }

    private static boolean ok(JsonNode result) {
        return result != null && result.path("ok").asBoolean();
    }

    private static JsonNode data(Invocation call) {
        return call.result().path("untrusted_data");
    }

    static List<String> summarize(Trial trial) {
        List<String> summary = new ArrayList<>();
        for (Invocation call : invocations(trial.transcript())) {
            String code = call.result() == null ? "pending" : call.result().path("error").path("code")
                    .asText(call.result().path("ok").asBoolean() ? "ok" : "unknown");
            summary.add("turn=" + call.turn() + " " + call.name() + " " + call.args() + " => " + code);
        }
        trial.trace().stream().filter(line -> line.contains("未录制条件") || line.contains("exception"))
                .forEach(summary::add);
        summary.add("states=" + trial.states() + " errors=" + trial.errorCodes());
        String last = trial.transcript().stream().filter(m -> AgentMessage.ASSISTANT.equals(m.role())
                && m.content() != null).reduce((one, two) -> two).map(AgentMessage::content).orElse("");
        summary.add("final=" + last.substring(0, Math.min(250, last.length())));
        return summary;
    }

    // ---------------------------------------------------------------- harness plumbing

    static final class RecordingSink implements AgentEventSink {
        private final List<String> events = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();
        private AgentLoop.BrowserTaskEvent browser;

        @Override public void emit(String name, Object payload) {
            events.add(name);
            if ("browser_task_required".equals(name)) browser = (AgentLoop.BrowserTaskEvent) payload;
            if ("error".equals(name) && payload instanceof Map<?, ?> map) errors.add(String.valueOf(map.get("code")));
        }

        AgentLoop.BrowserTaskEvent browser() {
            if (browser == null) throw new IllegalStateException("missing browser_task_required");
            return browser;
        }

        void drainInto(List<String> trace, List<String> errorCodes) {
            trace.addAll(events);
            errors.forEach(code -> trace.add("error: " + code));
            errorCodes.addAll(errors);
        }
    }

    /** One trial's world: a fresh job library, page budget and replay log. */
    static final class Scenario {
        final Group group;
        final JsonNode task;
        final List<ReplayCommand> replayed = new ArrayList<>();
        final Map<Long, JobDescriptionResponse> jobs = new LinkedHashMap<>();
        final Map<String, Long> ids = new LinkedHashMap<>();
        final Map<Long, Double> similarity = new HashMap<>();
        private final Map<String, Double> listedSimilarity = new HashMap<>();
        int turn;
        private int arrivals;
        private long nextId = 101;
        private int dailyPagesLeft = DAILY_PAGES;
        private final JobDescriptionService jobService = mock(JobDescriptionService.class);
        private final ResumeService resumes = mock(ResumeService.class);
        private final JobSearchPreferencesService preferences = mock(JobSearchPreferencesService.class);
        private final JobCollectionHistory history = mock(JobCollectionHistory.class);
        private final JobApplicationService applications = mock(JobApplicationService.class);

        Scenario(Group group, JsonNode task) throws IOException {
            this.group = group;
            this.task = task;
            JsonNode resume = group.profile().path("resume");
            ResumeResponse stored = new ResumeResponse(resume.path("resumeId").asLong(), USER_ID, "eval_user",
                    resume.path("title").asText(), resume.path("candidateName").asText(), null,
                    resume.path("email").asText(), "resume.pdf", "application/pdf", "pdf", 4096L,
                    resume.path("rawText").asText(), LocalDateTime.parse(resume.path("updatedAt").asText()),
                    LocalDateTime.parse(resume.path("updatedAt").asText()));
            when(resumes.findAllForAgent(anyInt(), anyInt(), any())).thenAnswer(inv -> resumePage(stored, inv.getArgument(2)));
            when(resumes.findAll(anyInt(), anyInt(), any())).thenAnswer(inv -> resumePage(stored, inv.getArgument(2)));
            when(resumes.findById(anyLong())).thenAnswer(inv -> {
                Long id = inv.getArgument(0);
                if (!stored.id().equals(id)) throw new ResourceNotFoundException("Resume", id);
                return stored;
            });
            // The business code under test decides which profile fields it understands; the rest are ignored.
            JobSearchPreferencesView view = JSON.copy()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .treeToValue(group.profile().path("preferences"), JobSearchPreferencesView.class);
            when(preferences.forUser(anyLong())).thenReturn(view);
            when(jobService.findById(anyLong())).thenAnswer(inv -> {
                Long id = inv.getArgument(0);
                JobDescriptionResponse job = jobs.get(id);
                if (job == null) throw new ResourceNotFoundException("JobDescription", id);
                return job;
            });
            when(jobService.captureListings(anyList(), any())).thenAnswer(inv -> {
                List<JobCaptureRequest> listings = inv.getArgument(0);
                String experience = inv.getArgument(1);
                List<ListingCaptureResult.Saved> saved = new ArrayList<>();
                for (JobCaptureRequest listing : listings) {
                    Long existing = ids.get(listing.sourceJobId());
                    if (existing != null) {
                        saved.add(new ListingCaptureResult.Saved(jobs.get(existing), true));
                        continue;
                    }
                    long id = nextId++;
                    JobDescriptionResponse job = job(id, listing, experience, "LISTING");
                    jobs.put(id, job);
                    ids.put(listing.sourceJobId(), id);
                    similarity.put(id, listedSimilarity.getOrDefault(listing.sourceJobId(), 0.1));
                    saved.add(new ListingCaptureResult.Saved(job, false));
                }
                return new ListingCaptureResult(saved, 0, false, 200);
            });
            when(jobService.capture(any())).thenAnswer(inv -> {
                JobCaptureRequest request = inv.getArgument(0);
                Long id = ids.get(request.sourceJobId());
                if (id == null) throw new IllegalStateException("detail capture for a job that was never listed");
                JobDescriptionResponse full = job(id, request, jobs.get(id).collectionExperience(), "FULL");
                jobs.put(id, full);
                return new JobCaptureResponse(full, true, true);
            });
            when(jobService.findAll(anyInt(), anyInt(), any(), any(), any())).thenAnswer(inv -> {
                int page = inv.getArgument(0);
                int size = inv.getArgument(1);
                String keyword = Objects.requireNonNullElse(inv.<String>getArgument(2), "").toLowerCase(Locale.ROOT);
                String type = Objects.requireNonNullElse(inv.<String>getArgument(3), "");
                String experience = Objects.requireNonNullElse(inv.<String>getArgument(4), "");
                List<JobDescriptionResponse> matching = jobs.values().stream()
                        .filter(job -> keyword.isBlank() || (job.title() + " " + job.companyName()).toLowerCase(Locale.ROOT).contains(keyword))
                        .filter(job -> type.isBlank() || type.equals(job.employmentType()))
                        .filter(job -> experience.isBlank() || experience.equals(job.collectionExperience()))
                        .toList();
                return page(matching, page, size);
            });
            when(jobService.libraryCounts()).thenAnswer(inv -> {
                Map<String, Long> byType = new TreeMap<>();
                jobs.values().forEach(job -> byType.merge(Objects.requireNonNullElse(job.employmentType(), "未知"), 1L, Long::sum));
                return new JobDescriptionService.LibraryCounts(jobs.size(), byType);
            });
        }

        long resumeId() {
            return group.profile().path("resume").path("resumeId").asLong();
        }

        Library library() {
            return new Library(Map.copyOf(ids), Map.copyOf(similarity));
        }

        private static PageResponse<ResumeResponse> resumePage(ResumeResponse resume, String keyword) {
            String filter = Objects.requireNonNullElse(keyword, "").strip();
            boolean matches = filter.isEmpty() || resume.title().contains(filter) || resume.candidateName().contains(filter);
            return page(matches ? List.of(resume) : List.of(), 0, 5);
        }

        private static <T> PageResponse<T> page(List<T> all, int page, int size) {
            int from = Math.min(all.size(), page * size);
            int totalPages = Math.max(1, (all.size() + size - 1) / size);
            return new PageResponse<>(all.subList(from, Math.min(all.size(), from + size)), page, size, all.size(),
                    totalPages, page == 0, page + 1 >= totalPages);
        }

        private static JobDescriptionResponse job(long id, JobCaptureRequest source, String experience, String depth) {
            return new JobDescriptionResponse(id, USER_ID, source.title(), source.companyName(), source.location(),
                    source.employmentType(), experience, source.description(), source.requirements(),
                    source.sourcePlatform(), source.sourceUrl(), source.sourceJobId(), null, null, depth, null, null);
        }

        /** Builds the browser's final event for a handed-out task from the frozen snapshots. */
        JsonNode report(AgentLoop.BrowserTaskEvent event, List<String> trace) throws IOException {
            if ("collect_boss_jobs".equals(event.tool())) {
                String keyword = Objects.toString(event.command().get("keyword"), "");
                String city = Objects.toString(event.command().get("city"), "");
                String experience = Objects.toString(event.command().get("experience"), "");
                replayed.add(new ReplayCommand(event.toolCallId(), turn, city, experience, keyword));
                trace.add("collector command: callId=" + event.toolCallId() + ",keyword=" + keyword
                        + ",city=" + city + ",experience=" + experience);
                String listing = listing(++arrivals, keyword, city, experience);
                if (listing == null) {
                    listing = GENERIC_LISTING;
                    trace.add("未录制条件: keyword=" + keyword + ",city=" + city + ",experience=" + experience);
                }
                ObjectNode report = (ObjectNode) read("snapshots/" + group.name() + "/listings/" + listing + ".json").deepCopy();
                ObjectNode result = (ObjectNode) report.get("task");
                result.set("command", JSON.valueToTree(event.command()));
                for (JsonNode row : result.path("jobs")) {
                    listedSimilarity.put(row.path("sourceJobId").asText(), row.path("evalSimilarity").asDouble(0.1));
                }
                return report;
            }
            if ("verify_boss_job".equals(event.tool())) {
                String sourceJobId = Objects.toString(event.command().get("sourceJobId"), "");
                String file = "snapshots/" + group.name() + "/details/" + sourceJobId + ".json";
                if (!SNAPSHOT_ID.matcher(sourceJobId).matches() || !Files.exists(ROOT.resolve(file))) {
                    throw new BusinessException("EVAL_SNAPSHOT_MISSING", "未录制详情 sourceJobId=" + sourceJobId);
                }
                ObjectNode item = (ObjectNode) read(file).deepCopy();
                item.put("sourcePlatform", "BOSS");
                item.put("sourceUrl", Objects.toString(event.command().get("sourceUrl"), ""));
                item.put("sourceJobId", sourceJobId);
                ObjectNode report = JSON.createObjectNode().put("type", "result");
                ObjectNode result = report.putObject("task");
                result.set("command", JSON.valueToTree(event.command()));
                result.put("status", "completed").put("finishReason", "DETAIL_READ").put("pagesRead", 1);
                result.putArray("jobs").add(item);
                trace.add("detail replay: " + sourceJobId);
                return report;
            }
            throw new IllegalStateException("unexpected browser tool " + event.tool());
        }

        /** First matching recorded search; null means the condition was not recorded. */
        private String listing(int attempt, String keyword, String city, String experience) {
            for (JsonNode search : task.path("searches")) {
                if (search.has("attempt") && search.path("attempt").asInt() != attempt) continue;
                if (search.has("city") && !search.path("city").asText().equals(city)) continue;
                if (search.has("experience") && !search.path("experience").asText().equals(experience)) continue;
                if (search.has("keywordSets") && !keywordRecorded(search.path("keywordSets"), keyword)) continue;
                return search.path("snapshot").asText();
            }
            return null;
        }

        private boolean keywordRecorded(JsonNode sets, String keyword) {
            String wanted = normalizeKeyword(keyword);
            for (JsonNode set : sets) {
                for (JsonNode word : group.keywordSets().path(set.asText())) {
                    if (normalizeKeyword(word.asText()).equals(wanted)) return true;
                }
            }
            return false;
        }

        AgentToolRegistry registry(ConversationStore store) {
            BrowserExtensionJobCollector extension = new BrowserExtensionJobCollector();
            // The real extension collector validates every replayed report; only the page budget is simulated.
            JobCollector collector = new JobCollector() {
                @Override public String platform() { return extension.platform(); }
                @Override public Start start(CollectRequest request) { return extension.start(request); }
                @Override public CollectionResult complete(CollectRequest request, JsonNode report) {
                    CollectionResult result = extension.complete(request, report);
                    dailyPagesLeft = Math.max(0, dailyPagesLeft - result.pagesRead());
                    return result.withDiagnostics(dailyPagesLeft, null);
                }
            };
            RoutingJobCollector details = new RoutingJobCollector(null, null, null) {
                @Override public Action startAction(Map<String, Object> rawCommand, CollectionSession session) {
                    Map<String, Object> command = new LinkedHashMap<>(rawCommand);
                    command.put("pageAllowance", 1);
                    return new Action(command, null, null, dailyPagesLeft);
                }
                @Override public Action completeAction(Map<String, Object> command, JsonNode report, CollectionSession session) {
                    dailyPagesLeft = Math.max(0, dailyPagesLeft - 1);
                    return new Action(command, report, null, dailyPagesLeft);
                }
            };
            RankJobsForResumeTool rankPrototype = new RankJobsForResumeTool(null);
            AgentTool rank = new AgentTool() {
                @Override public String name() { return rankPrototype.name(); }
                @Override public String description() { return rankPrototype.description(); }
                @Override public ToolSchema parameters() { return rankPrototype.parameters(); }
                @Override public Object execute(JsonNode args, AgentToolContext context) {
                    long resumeId = args.path("resumeId").asLong();
                    if (resumeId != resumeId()) throw new ResourceNotFoundException("Resume", resumeId);
                    List<Long> candidates = new ArrayList<>();
                    List<Long> missing = new ArrayList<>();
                    if (args.path("jobIds").isArray()) {
                        for (JsonNode value : args.path("jobIds")) {
                            if (jobs.containsKey(value.asLong())) candidates.add(value.asLong());
                            else missing.add(value.asLong());
                        }
                    } else candidates.addAll(jobs.keySet());
                    List<Map<String, Object>> matches = candidates.stream().distinct()
                            .sorted(Comparator.comparingDouble((Long id) -> -similarity.getOrDefault(id, 0.1))
                                    .thenComparing(Comparator.naturalOrder()))
                            .limit(args.path("limit").asInt(5))
                            .map(id -> Map.<String, Object>of("jobId", id, "title", jobs.get(id).title(),
                                    "companyName", jobs.get(id).companyName(), "similarity", similarity.getOrDefault(id, 0.1)))
                            .toList();
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("resumeId", resumeId);
                    result.put("matches", matches);
                    if (!missing.isEmpty()) result.put("notInLibrary", missing);
                    return result;
                }
            };
            return new AgentToolRegistry(List.of(
                    new ListResumesTool(resumes),
                    new GetResumeTool(resumes),
                    new GetJobSearchPreferencesTool(preferences),
                    new SearchJobsTool(jobService, history),
                    new CollectBossJobsTool(collector, jobService, history),
                    rank,
                    new VerifyBossJobTool(jobService, details),
                    new GetJobTool(jobService),
                    new CheckJobPreferencesTool(jobService, preferences),
                    new UpdateJobSearchContextTool(store),
                    new ListJobApplicationsTool(applications),
                    new SubmitRecommendationsTool(jobService, preferences)));
        }
    }

    /**
     * Offline reference solution: replays a task's {@code reference} tool calls through the real loop, so CI
     * proves each task is solvable and its checks accept a correct run. Placeholders: {@code $resume},
     * {@code $job:<sourceJobId>} and {@code $lastCollect} (jobIds of the latest successful collection).
     */
    static final class ReferenceAgentModel implements AgentModel {
        private final JsonNode reference;
        private final Scenario scenario;
        private int callIds;

        ReferenceAgentModel(JsonNode reference, Scenario scenario) {
            this.reference = reference;
            this.scenario = scenario;
        }

        @Override
        public Reply next(String systemPrompt, List<AgentMessage> transcript, List<ToolDefinition> tools, Duration timeout) {
            if (systemPrompt.startsWith("SUMMARY_TASK:")) return Reply.answer("参考解摘要");
            int lastUser = -1;
            int turn = -1;
            for (int index = 0; index < transcript.size(); index++) {
                if (AgentMessage.USER.equals(transcript.get(index).role())) {
                    lastUser = index;
                    turn++;
                }
            }
            int done = 0;
            for (AgentMessage message : transcript.subList(lastUser + 1, transcript.size())) {
                if (message.hasToolCalls()) done += message.toolCalls().size();
            }
            JsonNode steps = reference.path(turn);
            if (done >= steps.size()) return Reply.answer("参考解：本轮步骤已执行完毕。");
            JsonNode step = steps.get(done);
            return Reply.call(AgentMessage.ToolCall.of("ref_" + (++callIds), step.path("tool").asText(),
                    resolve(step.path("args"), transcript).toString()));
        }

        private JsonNode resolve(JsonNode node, List<AgentMessage> transcript) {
            if (node.isTextual()) {
                String text = node.asText();
                if ("$resume".equals(text)) return LongNode.valueOf(scenario.resumeId());
                if (text.startsWith("$job:")) {
                    Long id = scenario.ids.get(text.substring("$job:".length()));
                    if (id == null) throw new IllegalStateException("reference job was never collected: " + text);
                    return LongNode.valueOf(id);
                }
                if ("$lastCollect".equals(text)) {
                    for (Invocation call : invocations(transcript).reversed()) {
                        if (call.name().equals("collect_boss_jobs") && ok(call.result())) return data(call).path("jobIds");
                    }
                    throw new IllegalStateException("reference has no successful collection yet");
                }
                return node;
            }
            if (node.isObject()) {
                ObjectNode copy = JSON.createObjectNode();
                node.properties().forEach(entry -> copy.set(entry.getKey(), resolve(entry.getValue(), transcript)));
                return copy;
            }
            if (node.isArray()) {
                ArrayNode copy = JSON.createArrayNode();
                node.forEach(item -> copy.add(resolve(item, transcript)));
                return copy;
            }
            return node;
        }
    }
}

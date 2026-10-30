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
import com.arthur.jdragresume.dto.job.JobSemanticMatchResponse;
import com.arthur.jdragresume.dto.job.ListingCaptureResult;
import com.arthur.jdragresume.dto.resume.ResumeResponse;
import com.arthur.jdragresume.dto.user.JobSearchPreferencesView;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.exception.ResourceNotFoundException;
import com.arthur.jdragresume.service.JobApplicationService;
import com.arthur.jdragresume.service.JobDescriptionService;
import com.arthur.jdragresume.service.JobSearchPreferencesService;
import com.arthur.jdragresume.service.JobSemanticMatchService;
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

import com.arthur.jdragresume.agent.AgentEvalV3Grading.*;

import static com.arthur.jdragresume.agent.AgentEvalV3Grading.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test-only harness for evals/agent-v3. The loop is wired as in production ({@code AgentConfig}): the student
 * profile and {@link VerifyPriority} are in place, and every registered tool is the production class, ranking
 * included. Only storage, the semantic similarity and the browser are replaced by in-memory fakes that replay
 * the frozen snapshots; similarity comes from each row's evalSimilarity. Nothing here opens a browser, a
 * database or BOSS.
 */
final class AgentEvalV3 {
    static final Path ROOT = Path.of("..", "evals", "agent-v3");
    static final ObjectMapper JSON = JsonMapper.builder().findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    static final List<String> GROUPS = List.of("dev", "acceptance");
    /** Scoring semantics frozen with the acceptance inputs; included in {@link #acceptanceDigest()}. */
    static final String GRADING_SOURCE = "java/com/arthur/jdragresume/agent/AgentEvalV3Grading.java";
    static final int DAILY_PAGES = 30;
    static final String GENERIC_LISTING = "generic-unrecorded";
    private static final long USER_ID = 1L;
    /** Freshness is judged against the real clock; preloaded jobs are dated relative to it. */
    private static final java.time.Clock EVAL_CLOCK = java.time.Clock.systemUTC();
    private static final Pattern SNAPSHOT_ID = Pattern.compile("[a-z0-9-]{1,80}");
    /** Pending browser tasks and declined confirmations a single turn may go through before the harness gives up. */
    private static final int MAX_RESUMES_PER_TURN = 16;

    private AgentEvalV3() { }

    record Group(String name, JsonNode profile, JsonNode keywordSets, List<JsonNode> tasks) {
        JsonNode task(String id) {
            return tasks.stream().filter(task -> id.equals(task.path("id").asText())).findFirst().orElseThrow();
        }
    }

    record Trial(boolean passed, List<Check> checks, String category, List<AgentLoop.State> states,
                 List<String> errorCodes, List<AgentMessage> transcript, List<String> trace, Metrics metrics) { }

    /** The profile uses the business field names; fields the business code does not know are ignored. */
    static JobSearchPreferencesView preferencesView(JsonNode profilePreferences) throws IOException {
        return JSON.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .treeToValue(profilePreferences, JobSearchPreferencesView.class);
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

    /**
     * SHA-256 over every acceptance input and the scoring source, independent of line endings, so any edit
     * is visible in review.
     */
    static String acceptanceDigest() throws Exception {
        List<Path> files = new ArrayList<>(List.of(ROOT.resolve("tasks/acceptance.json"), ROOT.resolve(GRADING_SOURCE)));
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
                new BrowserTaskRegistry(), store, scenario.studentProfile(), scenario.verifyPriority());
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
                for (int resumes = 0; state == AgentLoop.State.AWAITING_BROWSER
                        || state == AgentLoop.State.AWAITING_CONFIRMATION; resumes++) {
                    if (resumes >= MAX_RESUMES_PER_TURN) throw new IllegalStateException("turn did not settle");
                    if (state == AgentLoop.State.AWAITING_CONFIRMATION) {
                        // No task asks to loosen a condition or start an analysis, so the simulated user declines.
                        AgentLoop.PendingConfirmation pending = sink.confirmation();
                        trace.add("confirmation declined: " + pending.tool());
                        var approval = new TranscriptPolicy.Approval(pending.toolCallId(), false);
                        sink = new RecordingSink();
                        output = loop.run(policy.normalize(messages, approval), approval, context, sink, () -> false);
                    } else {
                        AgentLoop.BrowserTaskEvent event = sink.browser();
                        JsonNode report = scenario.report(event, trace);
                        var pending = policy.normalize(messages, event.toolCallId(),
                                call -> ToolEnvelope.error(call.name(), "EVAL_ABANDONED", "replaced by browser result"));
                        var resume = new TranscriptPolicy.BrowserReport(event.toolCallId(), event.taskId(),
                                TranscriptPolicy.BrowserReport.COMPLETED, report);
                        sink = new RecordingSink();
                        output = loop.run(pending, null, context, sink, () -> false, System.currentTimeMillis(),
                                new AgentLoop.TurnOptions(null, List.of(), true, resume));
                    }
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
        Library library = scenario.library();
        List<Check> checks = grade(task, messages, scenario.replayed, library);
        String category = category(checks, messages, states, errors, exceptionCode);
        return new Trial("PASS".equals(category), checks, category, List.copyOf(states), List.copyOf(errors),
                List.copyOf(messages), List.copyOf(trace), metrics(task, messages, library));
    }

    // ---------------------------------------------------------------- reporting

    static List<String> summarize(Trial trial) {
        List<String> summary = new ArrayList<>();
        for (Invocation call : invocations(trial.transcript())) {
            String code = call.result() == null ? "pending" : call.result().path("error").path("code")
                    .asText(call.result().path("ok").asBoolean() ? "ok" : "unknown");
            summary.add("turn=" + call.turn() + " " + call.name() + " " + call.args() + " => " + code);
        }
        trial.trace().stream().filter(line -> line.contains("未录制条件") || line.contains("exception")
                || line.startsWith("confirmation declined")).forEach(summary::add);
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
        private AgentLoop.PendingConfirmation confirmation;

        @Override public void emit(String name, Object payload) {
            events.add(name);
            if ("browser_task_required".equals(name)) browser = (AgentLoop.BrowserTaskEvent) payload;
            if ("confirmation_required".equals(name)) confirmation = (AgentLoop.PendingConfirmation) payload;
            if ("error".equals(name) && payload instanceof Map<?, ?> map) errors.add(String.valueOf(map.get("code")));
        }

        AgentLoop.BrowserTaskEvent browser() {
            if (browser == null) throw new IllegalStateException("missing browser_task_required");
            return browser;
        }

        AgentLoop.PendingConfirmation confirmation() {
            if (confirmation == null) throw new IllegalStateException("missing confirmation_required");
            return confirmation;
        }

        void drainInto(List<String> trace, List<String> errorCodes) {
            trace.addAll(events);
            errors.forEach(code -> trace.add("error: " + code));
            errorCodes.addAll(errors);
        }
    }

    /** One trial's world: a fresh job library (with the task's preloaded jobs), page budget and replay log. */
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
        private final JobSemanticMatchService semantic = mock(JobSemanticMatchService.class);
        private final JdFreshness freshness = new JdFreshness(EVAL_CLOCK, new AgentProperties());

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
            when(preferences.forUser(anyLong())).thenReturn(preferencesView(group.profile().path("preferences")));
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
                    JobDescriptionResponse job = job(id, listing, experience, "LISTING", null);
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
                JobDescriptionResponse full = job(id, request, jobs.get(id).collectionExperience(), "FULL",
                        LocalDateTime.now(EVAL_CLOCK));
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
            // Similarity is the only stand-in: the production ranking tool then screens and orders these matches.
            when(semantic.rank(anyLong(), anyInt(), any())).thenAnswer(inv -> {
                long resumeId = inv.getArgument(0);
                if (resumeId != resumeId()) throw new ResourceNotFoundException("Resume", resumeId);
                int limit = inv.getArgument(1);
                Collection<Long> only = inv.getArgument(2);
                return jobs.values().stream()
                        .filter(job -> only == null || only.contains(job.id()))
                        .sorted(Comparator.comparingDouble((JobDescriptionResponse job) -> -similarity.getOrDefault(job.id(), 0.1))
                                .thenComparing(JobDescriptionResponse::id))
                        .limit(limit)
                        .map(job -> new JobSemanticMatchResponse(job, similarity.getOrDefault(job.id(), 0.1)))
                        .toList();
            });
            for (JsonNode preloaded : task.path("preload")) preload(preloaded.asText());
        }

        /** A job saved by an earlier session: FULL, read {@code verifiedDaysAgo} days ago from its library snapshot. */
        private void preload(String sourceJobId) throws IOException {
            JsonNode entry = read("snapshots/" + group.name() + "/library/" + sourceJobId + ".json");
            long id = nextId++;
            LocalDateTime verifiedAt = LocalDateTime.now(EVAL_CLOCK).minusDays(entry.path("verifiedDaysAgo").asLong());
            jobs.put(id, new JobDescriptionResponse(id, USER_ID, entry.path("title").asText(),
                    entry.path("companyName").asText(), entry.path("location").asText(),
                    entry.path("employmentType").asText(), "在校生", entry.path("description").asText(),
                    entry.path("requirements").asText(), entry.path("sourcePlatform").asText(),
                    entry.path("sourceUrl").asText(), sourceJobId, null, null, "FULL", verifiedAt, verifiedAt, verifiedAt));
            ids.put(sourceJobId, id);
            similarity.put(id, entry.path("evalSimilarity").asDouble(0.1));
        }

        long resumeId() {
            return group.profile().path("resume").path("resumeId").asLong();
        }

        Library library() {
            return new Library(Map.copyOf(ids), Map.copyOf(similarity));
        }

        /** As {@code AgentConfig.jobSearchProfile}: the student status the user saved on the preferences page. */
        JobSearchProfile studentProfile() {
            return userId -> userId == null ? null : preferences.forUser(userId).currentlyStudent();
        }

        VerifyPriority verifyPriority() {
            return new VerifyPriority(jobService, preferences, freshness);
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

        private static JobDescriptionResponse job(long id, JobCaptureRequest source, String experience, String depth,
                                                  LocalDateTime verifiedAt) {
            return new JobDescriptionResponse(id, USER_ID, source.title(), source.companyName(), source.location(),
                    source.employmentType(), experience, source.description(), source.requirements(),
                    source.sourcePlatform(), source.sourceUrl(), source.sourceJobId(), null, null, depth,
                    verifiedAt, null, null);
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
            JobSearchProfile profile = studentProfile();
            return new AgentToolRegistry(List.of(
                    new ListResumesTool(resumes),
                    new GetResumeTool(resumes),
                    new GetJobSearchPreferencesTool(preferences),
                    new SearchJobsTool(jobService, history),
                    new CollectBossJobsTool(collector, jobService, history),
                    new RankJobsForResumeTool(semantic, preferences, freshness, store),
                    new VerifyBossJobTool(jobService, details, freshness),
                    new GetJobTool(jobService),
                    new CheckJobPreferencesTool(jobService, preferences),
                    new UpdateJobSearchContextTool(store, profile),
                    new RelaxJobSearchConditionTool(store, profile),
                    new ListJobApplicationsTool(applications),
                    new SubmitRecommendationsTool(jobService, preferences, resumes, freshness, store)));
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

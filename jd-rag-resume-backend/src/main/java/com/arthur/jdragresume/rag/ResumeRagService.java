package com.arthur.jdragresume.rag;

import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.entity.Resume;
import com.arthur.jdragresume.entity.ResumeChunk;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.repository.ResumeChunkRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ResumeRagService {
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}+#./_-]{1,40}");
    private static final Map<String, List<String>> HARD_SKILL_ALIASES = hardSkillAliases();

    private final EmbeddingModel embeddingModel;
    private final ResumeChunkRepository resumeChunkRepository;
    private final TextChunker textChunker;
    private final RagProperties properties;
    private final ObjectMapper objectMapper;
    private final ResumeIndexStore resumeIndexStore;
    private final LuceneVectorIndex vectorIndex;
    private final Object[] indexLocks = createIndexLocks(64);

    public ResumeRagService(
            EmbeddingModel embeddingModel,
            ResumeChunkRepository resumeChunkRepository,
            TextChunker textChunker,
            RagProperties properties,
            ObjectMapper objectMapper,
            ResumeIndexStore resumeIndexStore,
            LuceneVectorIndex vectorIndex
    ) {
        this.embeddingModel = embeddingModel;
        this.resumeChunkRepository = resumeChunkRepository;
        this.textChunker = textChunker;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.resumeIndexStore = resumeIndexStore;
        this.vectorIndex = vectorIndex;
    }

    public List<RetrievedChunk> retrieve(AppUser user, Resume resume, JobDescription jobDescription) {
        try {
            List<ResumeChunk> indexedChunks = ensureIndexed(user, resume);
            List<float[]> queryEmbeddings = buildQueryEmbeddings(jobDescription);
            List<String> keywords = extractKeywords(jobDescription);

            int candidateLimit = Math.max(properties.getTopK() * 4, 20);
            Map<Integer, ResumeChunk> chunksByIndex = new LinkedHashMap<>();
            indexedChunks.forEach(chunk -> chunksByIndex.put(chunk.getChunkIndex(), chunk));
            List<ResumeChunk> candidates = vectorIndex.search(resume.getId(), queryEmbeddings, candidateLimit).stream()
                    .map(hit -> chunksByIndex.get(hit.chunkIndex()))
                    .filter(java.util.Objects::nonNull)
                    .toList();

            List<ScoredChunk> scored = new ArrayList<>();
            for (ResumeChunk chunk : candidates) {
                float[] chunkEmbedding = objectMapper.readValue(chunk.getEmbedding(), float[].class);
                double raw = maxCosine(queryEmbeddings, chunkEmbedding);
                List<String> boostHits = properties.isHybridEnabled()
                        ? matchKeywords(chunk.getContent(), keywords)
                        : List.of();
                double boosted = applyBoost(raw, boostHits.size());
                scored.add(new ScoredChunk(
                        chunk.getChunkIndex(),
                        chunk.getContent(),
                        raw,
                        boosted,
                        TextChunker.describeSections(resume.getRawText(), chunk.getContent()),
                        boostHits
                ));
            }

            scored.sort(Comparator.comparingDouble(ScoredChunk::boostedSimilarity).reversed());

            double minSimilarity = Math.max(0.0, Math.min(1.0, properties.getMinSimilarity()));
            int topK = Math.max(1, properties.getTopK());
            List<RetrievedChunk> result = new ArrayList<>();
            int keptCount = 0;

            for (ScoredChunk item : scored) {
                // Lexical boosts may reorder semantically relevant chunks, but must never
                // promote an unrelated chunk across the semantic evidence threshold.
                boolean passThreshold = item.rawSimilarity() >= minSimilarity;
                boolean kept = passThreshold && keptCount < topK;
                String status;
                if (kept) {
                    status = "kept";
                    keptCount += 1;
                } else if (passThreshold) {
                    status = "over-topk";
                } else {
                    status = "below-threshold";
                }
                result.add(new RetrievedChunk(
                        item.chunkIndex(),
                        item.content(),
                        item.boostedSimilarity(),
                        item.rawSimilarity(),
                        kept,
                        status,
                        item.section(),
                        item.boostHits()
                ));
            }

            return result;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("RAG_RETRIEVAL_FAILED", "failed to index or retrieve resume evidence");
        }
    }

    private List<ResumeChunk> ensureIndexed(AppUser user, Resume resume) throws Exception {
        // Include retrieval-side config so pooling / chunk changes force re-index.
        String sourceHash = sha256(
                properties.getEmbeddingModelId()
                        + "\nchunk=" + properties.getChunkSize()
                        + "\noverlap=" + properties.getChunkOverlap()
                        + "\noutput=" + properties.getModelOutputName()
                        + "\npooling=" + properties.getPoolingMode()
                        + "\nmaxLength=" + properties.getMaxLength()
                        + "\n" + resume.getRawText()
        );
        List<ResumeChunk> existing = findCurrentIndex(resume.getId(), sourceHash);
        if (!existing.isEmpty()) {
            ensureVectorIndex(resume.getId(), sourceHash, existing);
            return existing;
        }

        Object lock = indexLocks[Math.floorMod(resume.getId().hashCode(), indexLocks.length)];
        synchronized (lock) {
            existing = findCurrentIndex(resume.getId(), sourceHash);
            if (!existing.isEmpty()) {
                ensureVectorIndex(resume.getId(), sourceHash, existing);
                return existing;
            }
            return buildAndReplaceIndex(user, resume, sourceHash);
        }
    }

    private List<ResumeChunk> findCurrentIndex(Long resumeId, String sourceHash) {
        List<ResumeChunk> existing = resumeChunkRepository.findByResumeIdOrderByChunkIndexAsc(resumeId);
        if (!existing.isEmpty() && existing.stream().allMatch(chunk -> sourceHash.equals(chunk.getSourceHash()))) {
            return existing;
        }
        return List.of();
    }

    private List<ResumeChunk> buildAndReplaceIndex(AppUser user, Resume resume, String sourceHash) throws Exception {

        List<String> contents = textChunker.split(resume.getRawText());
        if (contents.isEmpty()) {
            throw new BusinessException("RESUME_TEXT_EMPTY", "resume rawText is empty");
        }

        List<float[]> embeddings = embedInBatches(contents);
        if (embeddings.size() != contents.size()) {
            throw new BusinessException("RAG_EMBEDDING_FAILED", "embedding result count does not match resume chunks");
        }

        List<ResumeChunk> replacements = new ArrayList<>();
        for (int index = 0; index < contents.size(); index++) {
            ResumeChunk chunk = new ResumeChunk();
            chunk.setUser(user);
            chunk.setResume(resume);
            chunk.setChunkIndex(index);
            chunk.setSourceHash(sourceHash);
            chunk.setContent(contents.get(index));
            chunk.setEmbedding(objectMapper.writeValueAsString(embeddings.get(index)));
            replacements.add(chunk);
        }

        List<ResumeChunk> saved = resumeIndexStore.replace(resume.getId(), replacements);
        vectorIndex.replace(resume.getId(), saved);
        return saved;
    }

    private void ensureVectorIndex(Long resumeId, String sourceHash, List<ResumeChunk> chunks) throws Exception {
        if (!vectorIndex.isCurrent(resumeId, sourceHash, chunks.size())) {
            vectorIndex.replace(resumeId, chunks);
        }
    }

    private static Object[] createIndexLocks(int count) {
        Object[] locks = new Object[count];
        java.util.Arrays.setAll(locks, ignored -> new Object());
        return locks;
    }

    private List<float[]> embedInBatches(List<String> contents) {
        int batchSize = Math.max(1, properties.getEmbeddingBatchSize());
        List<float[]> embeddings = new ArrayList<>(contents.size());
        for (int start = 0; start < contents.size(); start += batchSize) {
            int end = Math.min(start + batchSize, contents.size());
            embeddings.addAll(embeddingModel.embed(contents.subList(start, end)));
        }
        return embeddings;
    }

    private List<float[]> buildQueryEmbeddings(JobDescription jobDescription) {
        List<String> queries = new ArrayList<>();
        queries.add(queryText(jobDescription));
        if (properties.isDualQueryEnabled()) {
            String requirements = jobDescription.getRequirements() == null ? "" : jobDescription.getRequirements().trim();
            if (!requirements.isBlank()) {
                queries.add(properties.getQueryPrefix() + "\nRequirements: " + requirements);
            }
        }
        return embeddingModel.embed(queries);
    }

    private String queryText(JobDescription jobDescription) {
        return """
                %s
                Job title: %s
                Job description: %s
                Requirements: %s
                """.formatted(
                properties.getQueryPrefix(),
                jobDescription.getTitle(),
                jobDescription.getDescription(),
                jobDescription.getRequirements() == null ? "" : jobDescription.getRequirements()
        );
    }

    private List<String> extractKeywords(JobDescription jobDescription) {
        String blob = String.join("\n",
                nullToEmpty(jobDescription.getTitle()),
                nullToEmpty(jobDescription.getDescription()),
                nullToEmpty(jobDescription.getRequirements())
        );
        Set<String> keywords = new LinkedHashSet<>();
        Matcher matcher = TOKEN_PATTERN.matcher(blob);
        while (matcher.find()) {
            String token = matcher.group().trim();
            if (token.length() < 2) {
                continue;
            }
            String lower = token.toLowerCase(Locale.ROOT);
            if (isStopWord(lower)) {
                continue;
            }
            keywords.add(token);
            if (keywords.size() >= 48) {
                break;
            }
        }
        return List.copyOf(keywords);
    }

    private List<String> matchKeywords(String content, List<String> keywords) {
        if (content == null || content.isBlank() || keywords.isEmpty()) {
            return List.of();
        }
        String haystack = content.toLowerCase(Locale.ROOT);
        List<String> hits = new ArrayList<>();
        for (String keyword : keywords) {
            if (haystack.contains(keyword.toLowerCase(Locale.ROOT))) {
                hits.add(keyword);
                if (hits.size() >= Math.max(1, properties.getMaxKeywordBoosts())) {
                    break;
                }
            }
        }
        return hits;
    }

    public HardSkillCoverage assessHardSkills(JobDescription jobDescription, List<RetrievedChunk> keptChunks) {
        String jdText = String.join("\n",
                nullToEmpty(jobDescription.getTitle()),
                nullToEmpty(jobDescription.getDescription()),
                nullToEmpty(jobDescription.getRequirements())
        ).toLowerCase(Locale.ROOT);
        String evidence = keptChunks.stream()
                .filter(RetrievedChunk::kept)
                .map(RetrievedChunk::content)
                .filter(content -> content != null && !content.isBlank())
                .collect(java.util.stream.Collectors.joining("\n"))
                .toLowerCase(Locale.ROOT);

        List<String> required = new ArrayList<>();
        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        HARD_SKILL_ALIASES.forEach((canonical, aliases) -> {
            if (containsAlias(jdText, aliases)) {
                required.add(canonical);
                if (containsAlias(evidence, aliases)) {
                    matched.add(canonical);
                } else {
                    missing.add(canonical);
                }
            }
        });
        return new HardSkillCoverage(required, matched, missing);
    }

    private static boolean containsAlias(String text, List<String> aliases) {
        return aliases.stream().anyMatch(alias -> {
            String normalized = alias.toLowerCase(Locale.ROOT);
            if (normalized.chars().allMatch(ch -> ch < 128)) {
                // ASCII aliases only need ASCII boundaries. Using \p{L} here would treat an
                // adjacent CJK character as a word character, so "熟悉Java开发" and
                // "Java后端工程师" — the usual spacing in Chinese resumes and JDs — would
                // never match, silently dropping required skills and depressing the score.
                Pattern boundary = Pattern.compile(
                        "(?<![A-Za-z0-9])" + Pattern.quote(normalized) + "(?![A-Za-z0-9])",
                        Pattern.CASE_INSENSITIVE
                );
                return boundary.matcher(text).find();
            }
            return text.contains(normalized);
        });
    }

    private static Map<String, List<String>> hardSkillAliases() {
        Map<String, List<String>> skills = new LinkedHashMap<>();
        skills.put("Java", List.of("java"));
        skills.put("Spring Boot", List.of("spring boot", "springboot"));
        skills.put("Spring Cloud", List.of("spring cloud", "springcloud"));
        skills.put("MySQL", List.of("mysql"));
        skills.put("PostgreSQL", List.of("postgresql", "postgres"));
        skills.put("Redis", List.of("redis"));
        skills.put("Kafka", List.of("kafka"));
        skills.put("RabbitMQ", List.of("rabbitmq", "rabbit mq"));
        skills.put("Docker", List.of("docker"));
        skills.put("Kubernetes", List.of("kubernetes", "k8s"));
        skills.put("Python", List.of("python"));
        skills.put("JavaScript", List.of("javascript", "js"));
        skills.put("TypeScript", List.of("typescript", "ts"));
        skills.put("Vue", List.of("vue", "vue.js", "vue3"));
        skills.put("React", List.of("react", "react.js", "reactjs"));
        skills.put("Linux", List.of("linux"));
        skills.put("Git", List.of("git"));
        skills.put("Elasticsearch", List.of("elasticsearch", "elastic search", "es"));
        skills.put("RAG", List.of("rag", "retrieval augmented generation", "检索增强生成"));
        skills.put("LLM", List.of("llm", "大语言模型"));
        // 不能用 Map.copyOf：它的迭代顺序随 JVM 启动的随机种子变化，而这个顺序决定了
        // prompt 与「规则校验缺失」文案里技能的排列，同样的输入必须得到同样的 prompt。
        return Collections.unmodifiableMap(skills);
    }

    private double applyBoost(double rawSimilarity, int hitCount) {
        if (!properties.isHybridEnabled() || hitCount <= 0) {
            return rawSimilarity;
        }
        double boost = properties.getKeywordBoost() * hitCount;
        return Math.min(0.999, rawSimilarity + boost);
    }

    private double maxCosine(List<float[]> queries, float[] chunkEmbedding) {
        double best = 0.0;
        for (float[] query : queries) {
            best = Math.max(best, cosineSimilarity(query, chunkEmbedding));
        }
        return best;
    }

    private String sha256(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean isStopWord(String lower) {
        return Set.of(
                "and", "or", "the", "with", "for", "from", "this", "that", "your", "our",
                "以及", "或者", "相关", "工作", "经验", "熟悉", "负责", "岗位", "任职", "要求",
                "能力", "优先", "以上", "具有", "良好", "进行", "使用", "开发", "公司"
        ).contains(lower);
    }

    static double cosineSimilarity(float[] left, float[] right) {
        if (left.length == 0 || left.length != right.length) {
            return 0.0;
        }
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private record ScoredChunk(
            int chunkIndex,
            String content,
            double rawSimilarity,
            double boostedSimilarity,
            String section,
            List<String> boostHits
    ) {
    }
}

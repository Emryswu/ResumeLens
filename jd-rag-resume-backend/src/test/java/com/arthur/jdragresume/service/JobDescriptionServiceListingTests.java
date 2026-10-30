package com.arthur.jdragresume.service;

import com.arthur.jdragresume.dto.job.JobCaptureRequest;
import com.arthur.jdragresume.dto.job.ListingCaptureResult;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.CaptureDepth;
import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.repository.AppUserRepository;
import com.arthur.jdragresume.repository.JobDescriptionRepository;
import com.arthur.jdragresume.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Search-list summaries enter the library without ever replacing a fuller posting. */
class JobDescriptionServiceListingTests {
    private static final String FULL_JD = "岗位职责：负责支付核心链路的 Spring Boot 服务开发……（完整 JD 正文）";

    private final Map<String, JobDescription> store = new LinkedHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private int quota = 200;

    @BeforeEach
    void setUp() {
        store.clear();
        ids.set(0L);
    }

    @Test
    void newListingsAreSavedAsListingDepthWithEmbeddings() {
        ListingCaptureResult result = service().captureListings(List.of(listing("a1", "摘要 A"), listing("b2", "摘要 B")), "不限");

        assertEquals(2, result.saved().size());
        assertTrue(result.saved().stream().noneMatch(ListingCaptureResult.Saved::existingJob));
        assertEquals("LISTING", result.saved().getFirst().job().captureDepth());
        JobDescription stored = store.get("a1");
        assertEquals(CaptureDepth.LISTING, stored.getCaptureDepth());
        assertEquals("不限", stored.getCollectionExperience());
        assertNotNull(stored.getSemanticEmbedding(), "summaries must be rankable");
        assertNotNull(stored.getLastSeenAt());
        assertFalse(result.quotaReached());
    }

    @Test
    void aListingNeverOverwritesAFullPostingItOnlyMarksItSeen() {
        JobDescriptionService service = service();
        service.capture(full("a1", FULL_JD));
        JobDescription before = store.get("a1");
        String fingerprint = before.getContentFingerprint();
        before.setLastSeenAt(before.getLastSeenAt().minusDays(3));
        var seenBefore = before.getLastSeenAt();

        ListingCaptureResult result = service.captureListings(List.of(listing("a1", "（BOSS 搜索列表摘要）职位：Java")), "不限");

        JobDescription after = store.get("a1");
        assertTrue(result.saved().getFirst().existingJob());
        assertEquals(FULL_JD, after.getDescription());
        assertEquals(fingerprint, after.getContentFingerprint());
        assertEquals(CaptureDepth.FULL, after.getCaptureDepth());
        assertTrue(after.getLastSeenAt().isAfter(seenBefore));
        assertEquals("不限", after.getCollectionExperience());
    }

    @Test
    void recollectingAKnownPostingFillsItsPreviouslyUnknownExperienceFilter() {
        JobDescriptionService service = service();
        service.capture(full("known", FULL_JD));
        assertEquals(null, store.get("known").getCollectionExperience());
        service.captureListings(List.of(listing("known", "摘要不能覆盖全文")), "在校生");
        assertEquals("在校生", store.get("known").getCollectionExperience());
        assertEquals(FULL_JD, store.get("known").getDescription());
    }

    @Test
    void aDetailCaptureUpgradesASummaryToTheFullPosting() {
        JobDescriptionService service = service();
        service.captureListings(List.of(listing("a1", "摘要")), "不限");

        service.capture(full("a1", FULL_JD));

        assertEquals(FULL_JD, store.get("a1").getDescription());
        assertEquals(CaptureDepth.FULL, store.get("a1").getCaptureDepth());
    }

    @Test
    void theQuotaStopsNewListingsButKnownOnesStillCount() {
        quota = 2;
        JobDescriptionService service = service();
        service.captureListings(List.of(listing("a1", "摘要")), "不限");

        ListingCaptureResult result = service.captureListings(List.of(
                listing("b2", "摘要"), listing("c3", "摘要"), listing("a1", "摘要"), listing("d4", "摘要")), "不限");

        assertEquals(List.of("a1", "b2"), result.saved().stream().map(saved -> saved.job().sourceJobId()).sorted().toList());
        assertEquals(2, result.notSaved());
        assertTrue(result.quotaReached());
        assertEquals(2, result.quota());
        assertEquals(2, store.size());
    }

    @Test
    void duplicatesInOneBatchAreSavedOnce() {
        ListingCaptureResult result = service().captureListings(List.of(listing("a1", "摘要"), listing("a1", "摘要")), "不限");
        assertEquals(1, result.saved().size());
        assertEquals(1, store.size());
    }

    private JobDescriptionService service() {
        AppUser user = new AppUser();
        user.setUsername("arthur");
        ReflectionTestUtils.setField(user, "id", 1L);
        JobDescriptionRepository jobs = proxy(JobDescriptionRepository.class, (ignored, method, args) -> switch (method.getName()) {
            case "findByUserIdAndSourcePlatformAndSourceJobId" -> Optional.ofNullable(store.get((String) args[2]));
            case "countByUserId" -> (long) store.size();
            case "save" -> {
                JobDescription job = (JobDescription) args[0];
                if (job.getId() == null) ReflectionTestUtils.setField(job, "id", ids.incrementAndGet());
                store.put(job.getSourceJobId(), job);
                yield job;
            }
            case "toString" -> "JobDescriptionRepositoryListingDouble";
            default -> throw new UnsupportedOperationException(method.getName());
        });
        AppUserRepository users = proxy(AppUserRepository.class, (ignored, method, args) -> switch (method.getName()) {
            case "findByIdForUpdate" -> Optional.of(user);
            case "toString" -> "AppUserRepositoryListingDouble";
            default -> throw new UnsupportedOperationException(method.getName());
        });
        CurrentUserService currentUser = new CurrentUserService(null) {
            @Override
            public AppUser getCurrentUser() {
                return user;
            }
        };
        return new JobDescriptionService(jobs, currentUser, users, SemanticEmbeddingTestSupport.service(), null, quota,
                java.time.Clock.systemDefaultZone());
    }

    private static JobCaptureRequest listing(String id, String description) {
        return new JobCaptureRequest("Java 开发", "示例科技", "深圳", "全职", description, "本科",
                "BOSS", "https://www.zhipin.com/job_detail/" + id + ".html", id);
    }

    private static JobCaptureRequest full(String id, String description) {
        return new JobCaptureRequest("Java 开发", "示例科技", "深圳", "全职", description, "Java、Spring Boot",
                "BOSS", "https://www.zhipin.com/job_detail/" + id + ".html", id);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}

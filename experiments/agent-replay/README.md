# Agent job-search offline replay

Run: `cd jd-rag-resume-backend && ./mvnw -B -Dtest=AgentJobSearchReplayTests test`

`src/test/resources/agent/job-search-replay.json` fixes five search-tool traces:
student/Shanghai? No: Shenzhen internship filter, adjacent AI keywords,
deduplication across searches, zero-result recovery, daily cap and page/count limits.
The existing redacted BOSS response lives in `browser-extension/tests/fixtures/boss-joblist.sample.json`;
the test verifies its internship / full-time ground truth without logging account data.
Scenario `jobIds` are synthetic local DB IDs, not BOSS identifiers.

Every scenario asserts requested/read pages, unique candidate counts, refusal of unsafe
next calls, and the content of the resumption instruction. All are JUnit CI gates.
No live BOSS requests, paid LLM calls or model randomness.

**Scope:** these tests validate deterministic server workflow budgets and transcript
accounting. They do not yet score end-to-end LLM task completion, factual recommendations,
or precision/recall. For model comparisons, freeze separate gold labels and replay the
same tasks with controlled model settings; never describe these policy tests as model F1.

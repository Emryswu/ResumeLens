package com.arthur.jdragresume.rag;

import com.arthur.jdragresume.entity.JobDescription;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HardSkillRequirementTests {
    @ParameterizedTest
    @ValueSource(strings = {
            "任职要求：熟悉 Java。有 Kafka 经验者优先。熟悉 MySQL",
            "任职要求：熟悉 Java，有 Kafka 经验者优先，熟悉 MySQL",
            "任职要求：\n熟悉 Java\n有 Kafka 经验者优先\n熟悉 MySQL"
    })
    void inlinePreferenceAppliesOnlyToItsClauseWithinMandatoryRequirements(String requirements) {
        HardSkillCoverage coverage = assess(requirements, "Java 项目");

        assertEquals(List.of("Java", "MySQL"), coverage.required());
        assertEquals(List.of("Java"), coverage.matched());
        assertEquals(List.of("MySQL"), coverage.missing());
        assertEquals(List.of("Kafka"), coverage.preferred());
        assertEquals("75.00", coverage.scoreCap().toPlainString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"熟悉 Java，有 Kafka 经验者优先", "熟悉 Java 和 MySQL，有 Kafka 经验者优先"})
    void matchingAPreferredSkillDoesNotAddRulePointsOrRaiseTheMandatoryCap(String requirements) {
        HardSkillCoverage withoutKafka = assess(requirements, "Java 项目");
        HardSkillCoverage withKafka = assess(requirements, "Java、Kafka 项目");

        assertEquals(withoutKafka, withKafka);
        assertEquals(List.of("Kafka"), withKafka.preferred());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "熟悉 Java，了解 Redis、Kafka 者优先",
            "熟悉 Java；加分项：Redis、Kafka",
            "熟悉 Java。Redis、Kafka 是加分项",
            "熟悉 Java\n优先技能：Redis、Kafka",
            "Java required; Redis and Kafka preferred",
            "Java required; nice to have: Redis, Kafka",
            "Java required, Redis and Kafka preferred",
            "熟悉 Java\n加分项\nRedis\nKafka"
    })
    void preferredSkillsDoNotBecomeMandatoryOrReduceTheCap(String requirements) {
        HardSkillCoverage coverage = assess(requirements, "Java");

        assertEquals(List.of("Java"), coverage.required());
        assertEquals(List.of("Java"), coverage.matched());
        assertEquals(List.of(), coverage.missing());
        assertEquals(List.of("Redis", "Kafka"), coverage.preferred());
        assertEquals("100.00", coverage.scoreCap().toPlainString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Java 或 Python 任意一种",
            "熟悉 Java 或者 Python",
            "Java、Python 至少掌握一种",
            "Java/Python 二选一",
            "Java or Python"
    })
    void oneMemberSatisfiesAnAlternativeGroup(String requirements) {
        HardSkillCoverage coverage = assess(requirements, "Java");

        assertEquals(List.of("Java / Python（任选其一）"), coverage.required());
        assertEquals(coverage.required(), coverage.matched());
        assertEquals(List.of(), coverage.missing());
        assertEquals("100.00", coverage.scoreCap().toPlainString());
    }

    @Test
    void anUnsatisfiedGroupCountsAsOneMissingRequirement() {
        HardSkillCoverage coverage = assess("熟悉 Java；Redis 或 Kafka 任意一种；掌握 MySQL", "Java MySQL");

        assertEquals(List.of("Redis / Kafka（任选其一）"), coverage.missing());
        assertEquals("83.33", coverage.scoreCap().toPlainString());
    }

    @Test
    void preferredSkillsCannotSatisfyAMissingMandatorySkill() {
        HardSkillCoverage coverage = assess("必须掌握 Java，Redis 优先", "Redis");

        assertEquals(List.of("Java"), coverage.missing());
        assertEquals(List.of(), coverage.matched());
        assertEquals("50.00", coverage.scoreCap().toPlainString());
    }

    @Test
    void satisfyingAnAlternativeDoesNotHideAnotherMandatoryGap() {
        HardSkillCoverage coverage = assess("Java 或 Python 任意一种；必须掌握 Redis", "Java");

        assertEquals(List.of("Redis"), coverage.missing());
        assertEquals("75.00", coverage.scoreCap().toPlainString());
    }

    @Test
    void anAlternativeMarkerDoesNotAbsorbAFollowingMandatorySkill() {
        HardSkillCoverage coverage = assess("Java 或 Python 任意一种并掌握 Redis", "Java");

        assertEquals(List.of("Redis"), coverage.missing());
        assertEquals("75.00", coverage.scoreCap().toPlainString());
    }

    @Test
    void bonusOnlyRequirementsHaveNoHardGapsOrCap() {
        HardSkillCoverage coverage = assess("加分项：Redis、Kafka", "Java");

        assertEquals(List.of(), coverage.required());
        assertEquals(List.of(), coverage.missing());
        assertEquals(List.of("Redis", "Kafka"), coverage.preferred());
        assertEquals("100.00", coverage.scoreCap().toPlainString());
    }

    @Test
    void mandatoryHeadingsEndBonusSections() {
        HardSkillCoverage coverage = assess("加分项：\nRedis\nKafka\n必备技能：\nJava", "Java");

        assertEquals(List.of("Java"), coverage.required());
        assertEquals(List.of("Redis", "Kafka"), coverage.preferred());
    }

    @Test
    void distinctAlternativeGroupsRemainSeparateAndBothMustBeSatisfied() {
        HardSkillCoverage coverage = assess("Java 或 Python；Redis 或 Kafka", "Python");

        assertEquals(List.of("Java / Python（任选其一）"), coverage.matched());
        assertEquals(List.of("Redis / Kafka（任选其一）"), coverage.missing());
        assertEquals("75.00", coverage.scoreCap().toPlainString());
    }

    @Test
    void discardedEvidenceCannotSatisfyAnAlternativeGroup() {
        JobDescription job = new JobDescription();
        job.setRequirements("Java 或 Python");
        var service = new ResumeRagService(null, null, null, new RagProperties(), null, null, null);
        HardSkillCoverage coverage = service.assessHardSkills(job, List.of(new RetrievedChunk(
                0, "Java", .1, .1, false, "below-threshold", "技能", List.of())));

        assertEquals(List.of("Java / Python（任选其一）"), coverage.missing());
        assertEquals("50.00", coverage.scoreCap().toPlainString());
    }

    @Test
    void titleDoesNotTurnAnAlternativeIntoAnAdditionalMandatorySkill() {
        JobDescription job = new JobDescription();
        job.setTitle("Java 后端工程师");
        job.setRequirements("Java 或 Python 任意一种");
        var service = new ResumeRagService(null, null, null, new RagProperties(), null, null, null);
        HardSkillCoverage coverage = service.assessHardSkills(job, List.of(new RetrievedChunk(
                0, "Python", .8, .8, true, "kept", "技能", List.of())));

        assertEquals(List.of("Java / Python（任选其一）"), coverage.matched());
        assertEquals(List.of(), coverage.missing());
    }

    @Test
    void bonusSectionInDescriptionDoesNotChangeRequirementsInAnotherField() {
        JobDescription job = new JobDescription();
        job.setDescription("加分项：Redis");
        job.setRequirements("熟悉 Java");
        var service = new ResumeRagService(null, null, null, new RagProperties(), null, null, null);
        HardSkillCoverage coverage = service.assessHardSkills(job, List.of());

        assertEquals(List.of("Java"), coverage.missing());
        assertEquals(List.of("Redis"), coverage.preferred());
    }

    @Test
    void anExplicitMandatoryOccurrenceWinsOverAPreferredOccurrence() {
        HardSkillCoverage coverage = assess("必须掌握 Java；Java、Redis 项目经验优先", "Redis");

        assertEquals(List.of("Java"), coverage.missing());
        assertEquals(List.of("Redis"), coverage.preferred());
    }

    private static HardSkillCoverage assess(String requirements, String evidence) {
        JobDescription job = new JobDescription();
        job.setRequirements(requirements);
        var service = new ResumeRagService(null, null, null, new RagProperties(), null, null, null);
        return service.assessHardSkills(job, List.of(new RetrievedChunk(
                0, evidence, .8, .8, true, "kept", "技能", List.of())));
    }
}

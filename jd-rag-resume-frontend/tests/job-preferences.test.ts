import test from "node:test";
import assert from "node:assert/strict";
import { choiceLabel, EDUCATION_OPTIONS, preferencePayload, STUDENT_OPTIONS, toPreferenceDraft } from "../app/job-preferences.ts";

test("missing fields remain null; district remains a preference by default", () => {
  const draft = toPreferenceDraft({
    configured: false, availableMonths: null, daysPerWeek: null,
    earliestStartDate: null, preferredDistrict: null, districtRequired: false,
    educationLevel: null, expectedGraduationDate: null, workExperienceYears: null, currentlyStudent: null,
  });
  assert.deepEqual(preferencePayload(draft), {
    availableMonths: null, daysPerWeek: null, earliestStartDate: null,
    preferredDistrict: null, districtRequired: false,
    educationLevel: null, expectedGraduationDate: null, workExperienceYears: null, currentlyStudent: null,
  });
});

test("saves explicit availability and normalizes district whitespace", () => {
  const draft = toPreferenceDraft({
    configured: true, availableMonths: 6, daysPerWeek: 5,
    earliestStartDate: "2026-11-01", preferredDistrict: "龙岗区", districtRequired: true,
    educationLevel: 2, expectedGraduationDate: "2027-06-30", workExperienceYears: 0, currentlyStudent: true,
  });
  draft.preferredDistrict = "  深圳龙岗区 ";
  assert.deepEqual(preferencePayload(draft), {
    availableMonths: 6, daysPerWeek: 5, earliestStartDate: "2026-11-01",
    preferredDistrict: "深圳龙岗区", districtRequired: true,
    educationLevel: 2, expectedGraduationDate: "2027-06-30", workExperienceYears: 0, currentlyStudent: true,
  });
});

test("choiceLabel resolves known values and ignores unknown or prototype keys", () => {
  assert.equal(choiceLabel(EDUCATION_OPTIONS, "2"), "本科");
  assert.equal(choiceLabel(STUDENT_OPTIONS, "yes"), "在校生");
  for (const key of ["99", "constructor", "__proto__", "toString", "valueOf"]) {
    assert.equal(choiceLabel(EDUCATION_OPTIONS, key), "暂未填写", key);
    assert.equal(choiceLabel(STUDENT_OPTIONS, key), "暂未填写", key);
  }
});

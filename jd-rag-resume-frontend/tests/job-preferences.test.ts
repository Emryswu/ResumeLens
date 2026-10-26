import test from "node:test";
import assert from "node:assert/strict";
import { preferencePayload, toPreferenceDraft } from "../app/job-preferences.ts";

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

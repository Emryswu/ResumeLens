import test from "node:test";
import assert from "node:assert/strict";
import { preferencePayload, toPreferenceDraft } from "../app/job-preferences.ts";

test("missing fields remain null; district remains a preference by default", () => {
  const draft = toPreferenceDraft({
    configured: false, availableMonths: null, daysPerWeek: null,
    earliestStartDate: null, preferredDistrict: null, districtRequired: false,
  });
  assert.deepEqual(preferencePayload(draft), {
    availableMonths: null, daysPerWeek: null, earliestStartDate: null,
    preferredDistrict: null, districtRequired: false,
  });
});

test("saves explicit availability and normalizes district whitespace", () => {
  const draft = toPreferenceDraft({
    configured: true, availableMonths: 6, daysPerWeek: 5,
    earliestStartDate: "2026-11-01", preferredDistrict: "龙岗区", districtRequired: true,
  });
  draft.preferredDistrict = "  深圳龙岗区 ";
  assert.deepEqual(preferencePayload(draft), {
    availableMonths: 6, daysPerWeek: 5, earliestStartDate: "2026-11-01",
    preferredDistrict: "深圳龙岗区", districtRequired: true,
  });
});

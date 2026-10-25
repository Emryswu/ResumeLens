/** Shared API shape; null means the user has not supplied that constraint. */
export type JobPreferences = {
  configured: boolean;
  availableMonths: number | null;
  daysPerWeek: number | null;
  earliestStartDate: string | null;
  preferredDistrict: string | null;
  districtRequired: boolean;
  educationLevel: number | null;
  expectedGraduationDate: string | null;
  workExperienceYears: number | null;
  currentlyStudent: boolean | null;
};

export type PreferenceDraft = {
  months: string;
  days: string;
  earliestStartDate: string;
  preferredDistrict: string;
  districtRequired: boolean;
  educationLevel: string;
  expectedGraduationDate: string;
  workExperienceYears: string;
  currentlyStudent: string;
};

export function toPreferenceDraft(value: JobPreferences): PreferenceDraft {
  return {
    months: value.availableMonths?.toString() ?? "",
    days: value.daysPerWeek?.toString() ?? "",
    earliestStartDate: value.earliestStartDate ?? "",
    preferredDistrict: value.preferredDistrict ?? "",
    districtRequired: value.districtRequired,
    educationLevel: value.educationLevel?.toString() ?? "",
    expectedGraduationDate: value.expectedGraduationDate ?? "",
    workExperienceYears: value.workExperienceYears?.toString() ?? "",
    currentlyStudent: value.currentlyStudent === null ? "" : value.currentlyStudent ? "yes" : "no",
  };
}

export function preferencePayload(draft: PreferenceDraft) {
  return {
    availableMonths: draft.months === "" ? null : Number(draft.months),
    daysPerWeek: draft.days === "" ? null : Number(draft.days),
    earliestStartDate: draft.earliestStartDate || null,
    preferredDistrict: draft.preferredDistrict.trim() || null,
    districtRequired: draft.districtRequired,
    educationLevel: draft.educationLevel === "" ? null : Number(draft.educationLevel),
    expectedGraduationDate: draft.expectedGraduationDate || null,
    workExperienceYears: draft.workExperienceYears === "" ? null : Number(draft.workExperienceYears),
    currentlyStudent: draft.currentlyStudent === "" ? null : draft.currentlyStudent === "yes",
  };
}

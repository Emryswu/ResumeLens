/** Shared API shape; null means the user has not supplied that constraint. */
export type JobPreferences = {
  configured: boolean;
  availableMonths: number | null;
  daysPerWeek: number | null;
  earliestStartDate: string | null;
  preferredDistrict: string | null;
  districtRequired: boolean;
};

export type PreferenceDraft = {
  months: string;
  days: string;
  earliestStartDate: string;
  preferredDistrict: string;
  districtRequired: boolean;
};

export function toPreferenceDraft(value: JobPreferences): PreferenceDraft {
  return {
    months: value.availableMonths?.toString() ?? "",
    days: value.daysPerWeek?.toString() ?? "",
    earliestStartDate: value.earliestStartDate ?? "",
    preferredDistrict: value.preferredDistrict ?? "",
    districtRequired: value.districtRequired,
  };
}

export function preferencePayload(draft: PreferenceDraft) {
  return {
    availableMonths: draft.months === "" ? null : Number(draft.months),
    daysPerWeek: draft.days === "" ? null : Number(draft.days),
    earliestStartDate: draft.earliestStartDate || null,
    preferredDistrict: draft.preferredDistrict.trim() || null,
    districtRequired: draft.districtRequired,
  };
}

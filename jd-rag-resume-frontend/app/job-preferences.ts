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

export type ChoiceOption = {
  value: string;
  label: string;
  description: string;
};

export const EDUCATION_OPTIONS: readonly ChoiceOption[] = [
  { value: "", label: "暂未填写", description: "遇到学历门槛时保留未知" },
  { value: "1", label: "专科", description: "已取得或就读专科学历" },
  { value: "2", label: "本科", description: "已取得或就读本科学历" },
  { value: "3", label: "硕士", description: "已取得或就读硕士学历" },
  { value: "4", label: "博士", description: "已取得或就读博士学历" },
];

export const STUDENT_OPTIONS: readonly ChoiceOption[] = [
  { value: "", label: "暂未填写", description: "不会根据简历猜测" },
  { value: "yes", label: "在校生", description: "目前仍在学校就读" },
  { value: "no", label: "已离校", description: "目前没有在校就读" },
];

/** Resolves an option label without prototype-chain lookup when reading server-supplied values. */
export function choiceLabel(options: readonly ChoiceOption[], value: string, fallback = "暂未填写"): string {
  return options.find((option) => option.value === value)?.label ?? fallback;
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

/** This is a user's manually maintained record, not a BOSS application status. */
export type ApplicationStatus =
  | "UNTRACKED" | "SAVED" | "APPLIED" | "INTERVIEWING"
  | "OFFER" | "REJECTED" | "NOT_INTERESTED";

export const APPLICATION_OPTIONS: ReadonlyArray<{
  value: ApplicationStatus;
  label: string;
  description: string;
}> = [
  { value: "UNTRACKED", label: "未跟踪", description: "清除这份记录" },
  { value: "SAVED", label: "待投递", description: "先收藏，准备后续申请" },
  { value: "APPLIED", label: "已投递", description: "已向招聘方提交申请" },
  { value: "INTERVIEWING", label: "面试中", description: "已进入面试流程" },
  { value: "OFFER", label: "收到 Offer", description: "已收到录用意向" },
  { value: "REJECTED", label: "未通过", description: "申请被拒或流程结束" },
  { value: "NOT_INTERESTED", label: "不合适", description: "以后推荐时尽量排除" },
];

export function applicationWrite(status: ApplicationStatus, note: string): RequestInit {
  return status === "UNTRACKED"
    ? { method: "DELETE" }
    : { method: "PUT", body: JSON.stringify({ status, note: note.trim() }) };
}

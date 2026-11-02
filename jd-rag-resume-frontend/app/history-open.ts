/**
 * Opening a row on the 分析记录 page. List rows carry no JD snapshot, so the report shown must be the
 * detail fetched by id, never the row itself. Only the latest click is applied, and only while the
 * login session that clicked is still current.
 */
export type HistoryOpenDeps<T> = {
  fetchById: (id: number) => Promise<T>;
  isSessionCurrent: () => boolean;
  show: (report: T) => void;
  fail: (reason: unknown) => void;
};

export function createHistoryOpener<T>() {
  let latest = 0;
  return async function open(id: number, deps: HistoryOpenDeps<T>): Promise<void> {
    const request = ++latest;
    let report: T;
    try {
      report = await deps.fetchById(id);
    } catch (reason) {
      if (request === latest && deps.isSessionCurrent()) deps.fail(reason);
      return;
    }
    if (request === latest && deps.isSessionCurrent()) deps.show(report);
  };
}

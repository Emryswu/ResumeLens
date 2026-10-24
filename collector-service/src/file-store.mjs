import { readFile, writeFile, rename, mkdir } from "node:fs/promises";
import { dirname } from "node:path";

/** A serialized, atomically replaced store for task state and page counters. Malformed files fail explicitly. */
export async function fileStore(path) {
  let data;
  try { data = JSON.parse(await readFile(path, "utf8")); }
  catch (error) { if (error.code !== "ENOENT") throw error; data = {}; }
  await mkdir(dirname(path), { recursive: true });
  let queue = Promise.resolve();
  function change(action) {
    const next = queue.then(async () => {
      action();
      await writeFile(path + ".tmp", JSON.stringify(data), "utf8");
      await rename(path + ".tmp", path);
    });
    queue = next;
    return next;
  }
  return {
    async get(key) { await queue; return structuredClone(data[key]); },
    async keys() { await queue; return Object.keys(data); },
    set(key, value) { return change(() => { data[key] = structuredClone(value); }); },
    remove(key) { return change(() => { delete data[key]; }); },
  };
}

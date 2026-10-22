/**
 * Turns a captured BOSS list API response into a sample that is safe to commit.
 *
 *   node scripts/sanitize-boss-sample.cjs <captured.json> <output.json>
 *
 * Every key is kept so the sample still shows the real shape. Values are kept only for
 * descriptive fields that identify no person or account (job title, salary range, tags, city,
 * industry); job ids and company names become stable placeholders so the sample stays
 * parseable; every other string becomes "REDACTED" and every other number 0. The rules are
 * idempotent, which tests/boss-api-extractor.test.cjs uses to check the committed sample.
 */
const fs = require("node:fs");

const KEEP_KEYS = new Set([
  "code",
  "message",
  "hasMore",
  "totalCount",
  "resCount",
  "jobName",
  "salaryDesc",
  "jobLabels",
  "skills",
  "jobExperience",
  "jobDegree",
  "cityName",
  "areaDistrict",
  "businessDistrict",
  "welfareList",
  "brandIndustry",
  "brandScaleName",
  "brandStageName",
  "jobType",
  "jobValidStatus",
  "daysPerWeekDesc",
  "leastMonthDesc",
]);
const PLACEHOLDERS = Object.freeze({
  encryptJobId: (index) => `sampleJob${index}`,
  brandName: (index) => `示例公司${index}`,
});

function sanitize(value) {
  const seen = Object.fromEntries(Object.keys(PLACEHOLDERS).map((key) => [key, new Map()]));
  return walk(value, null);

  function walk(node, key) {
    if (Array.isArray(node)) return node.map((item) => walk(item, key));
    if (node && typeof node === "object") {
      return Object.fromEntries(Object.entries(node).map(([childKey, child]) => [childKey, walk(child, childKey)]));
    }
    if (key && Object.prototype.hasOwnProperty.call(PLACEHOLDERS, key) && typeof node === "string" && node) {
      const names = seen[key];
      if (!names.has(node)) names.set(node, PLACEHOLDERS[key](names.size + 1));
      return names.get(node);
    }
    if (key && KEEP_KEYS.has(key)) return node;
    if (typeof node === "string") return node === "" ? "" : "REDACTED";
    if (typeof node === "number") return 0;
    return node;
  }
}

if (require.main === module) {
  const [input, output] = process.argv.slice(2);
  if (!input || !output) {
    console.error("usage: node scripts/sanitize-boss-sample.cjs <captured.json> <output.json>");
    process.exit(2);
  }
  const captured = JSON.parse(fs.readFileSync(input, "utf8").replace(/^﻿/, ""));
  fs.writeFileSync(output, `${JSON.stringify(sanitize(captured), null, 2)}\n`);
}

module.exports = { KEEP_KEYS, sanitize };

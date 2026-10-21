const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const search = require("../src/boss-search.js");

const root = path.join(__dirname, "..");
const manifest = JSON.parse(fs.readFileSync(path.join(root, "manifest.json"), "utf8"));

test("BOSS access is never a standing permission", () => {
  assert.ok(manifest.permissions.includes("activeTab"));
  assert.ok(manifest.permissions.includes("scripting"));
  assert.equal(manifest.host_permissions.some((value) => value.includes("zhipin.com")), false);
  assert.equal(
    manifest.content_scripts.some((script) => script.matches.some((value) => value.includes("zhipin.com"))),
    false,
  );
});

test("the optional BOSS permission is exactly what the code requests", () => {
  assert.deepEqual(manifest.optional_host_permissions, [...search.PERMISSION_ORIGINS]);
});

test("no permission beyond what collection needs", () => {
  assert.deepEqual([...manifest.permissions].sort(), ["activeTab", "scripting", "storage"]);
});

test("every script the manifest and the worker load exists", () => {
  const files = [
    manifest.background.service_worker,
    ...manifest.content_scripts.flatMap((script) => script.js),
  ];
  const worker = fs.readFileSync(path.join(root, manifest.background.service_worker), "utf8");
  const imported = /importScripts\(([^)]*)\)/.exec(worker)[1].match(/"([^"]+)"/g).map((value) => `src/${value.slice(1, -1)}`);
  for (const file of [...files, ...imported]) {
    assert.ok(fs.existsSync(path.join(root, file)), `${file} is missing`);
  }
});

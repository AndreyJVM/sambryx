import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import vm from "node:vm";
import ts from "typescript";

const source = ts.transpileModule(
  readFileSync(new URL("../src/lib/api.ts", import.meta.url), "utf8"),
  { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } },
).outputText;

function harness(cookie, fetchResponse) {
  const document = { cookie };
  const calls = [];
  const exports = {};
  vm.runInNewContext(source, {
    exports,
    document,
    Headers,
    Event,
    window: { dispatchEvent: () => {}, location: { pathname: "/ui/login", href: "" } },
    fetch: async (url, options) => {
      calls.push({ url, ...options });
      return fetchResponse(url, options, document);
    },
  });
  return { api: exports.api, calls };
}

function response(status = 200) {
  return { status, ok: status < 400, json: async () => ({ success: status < 400, data: {}, message: "Rejected" }) };
}

test("first login bootstraps a cookie token before sending credentials", async () => {
  const { api, calls } = harness("", (url, options, document) => {
    if (url === "/api/auth/environment") document.cookie = "XSRF-TOKEN=bootstrap-token";
    return response();
  });

  await api.post("/api/auth/login", { username: "fixture", password: "synthetic-fixture" });

  assert.deepEqual(calls.map(({ url }) => url), ["/api/auth/environment", "/api/auth/login"]);
  assert.equal(calls[0].body, undefined);
  assert.equal(calls[1].headers.get("X-XSRF-TOKEN"), "bootstrap-token");
  assert.equal(calls[1].method, "POST");
  assert.equal(JSON.parse(calls[1].body).username, "fixture");
});

test("logout uses the existing cookie token without an extra request", async () => {
  const { api, calls } = harness("XSRF-TOKEN=current-token", () => response());

  await api.post("/api/auth/logout");

  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, "/api/auth/logout");
  assert.equal(calls[0].headers.get("X-XSRF-TOKEN"), "current-token");
});

test("safe reads do not bootstrap a token", async () => {
  const { api, calls } = harness("", () => response());

  await api.get("/api/auth/environment");

  assert.equal(calls.length, 1);
  assert.equal(calls[0].headers.has("X-XSRF-TOKEN"), false);
});

test("missing cookie after bootstrap prevents sending credentials", async () => {
  const { api, calls } = harness("", () => response());

  await assert.rejects(api.post("/api/auth/login", { password: "synthetic-fixture" }), { status: 403 });

  assert.deepEqual(calls.map(({ url }) => url), ["/api/auth/environment"]);
});

test("a failed token bootstrap prevents the state changing request", async () => {
  const { api, calls } = harness("", () => { throw new TypeError("Network unavailable"); });

  await assert.rejects(api.post("/api/auth/logout"), { name: "ApiError" });

  assert.deepEqual(calls.map(({ url }) => url), ["/api/auth/environment"]);
});

test("a rejected state changing request is never automatically retried", async () => {
  const { api, calls } = harness("XSRF-TOKEN=current-token", () => response(403));

  await assert.rejects(api.post("/api/auth/login", { password: "synthetic-fixture" }), { status: 403 });

  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, "/api/auth/login");
});

test("host key failures retain their structured details without retrying login", async () => {
  const { api, calls } = harness("XSRF-TOKEN=fixture", () => ({
    status: 409,
    ok: false,
    json: async () => ({ success: false, message: "SSH identity rejected", data: {
      code: "SSH_HOST_KEY_CHANGED", fingerprint: "SHA256:fixture",
    } }),
  }));
  await assert.rejects(api.post("/api/auth/login", { host: "fixture.test" }), error => {
    assert.equal(error.status, 409);
    assert.equal(error.details.code, "SSH_HOST_KEY_CHANGED");
    assert.equal(error.details.fingerprint, "SHA256:fixture");
    return true;
  });
  assert.equal(calls.length, 1);
});

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import vm from "node:vm";
import ts from "typescript";

const api = {};
const errors = {};
const context = vm.createContext({ exports: api, require: () => api });
for (const [name, exported] of [["api", api], ["errors", errors]]) {
  context.exports = exported;
  const source = ts.transpileModule(readFileSync(new URL(`../src/lib/${name}.ts`, import.meta.url), "utf8"),
    { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText;
  vm.runInContext(source, context);
}

for (const [code, expected] of [
  ["SSH_HOST_KEY_CHANGED", "возможна подмена сервера"],
  ["SSH_HOST_KEY_UNKNOWN", "ещё не добавлен в доверенные"],
  ["SSH_HOST_KEY_UNAVAILABLE", "проверить подключение и права файла"],
]) {
  test(`host identity failure ${code} gives an actionable message`, () => {
    const message = errors.getErrorMessage(new api.ApiError("Internal message", 409, {code, fingerprint: "SHA256:fixture"}));
    assert(message.includes(expected));
    assert(message.includes("SHA256:fixture"));
    assert(!message.includes("Internal message"));
  });
}

test("unrelated API errors preserve their existing message", () => {
  assert.equal(errors.getErrorMessage(new api.ApiError("Existing error", 400)), "Existing error");
});

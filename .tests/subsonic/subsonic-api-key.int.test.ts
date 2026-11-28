import assert from "node:assert/strict";
import { test, beforeAll, afterAll } from "vite-plus/test";
import { subsonicPayload } from "./subsonicPayload.ts";
import {
  cleanupIsolatedState,
  resetDatabase,
  setupIsolatedBackend,
  startServerProcess,
} from "../helpers/backendTestHarness.ts";

const [isolatedState, { db }, { dbOps, userOps }, { hashPassword }] = await setupIsolatedBackend(
  "subsonic-api-key",
  () => import("../../backend/config/db-sqlite.ts"),
  () => import("../../backend/db/helpers/index.ts"),
  () => import("../../backend/services/passwordHash.ts"),
);

let aurral: Awaited<ReturnType<typeof startServerProcess>>;

async function ping(headers: Record<string, string>) {
  const query = new URLSearchParams({
    u: "alice",
    p: "password123",
    v: "1.16.1",
    c: "test",
    f: "json",
  });
  const response = await fetch(`http://127.0.0.1:${aurral.port}/rest/ping.view?${query}`, {
    headers,
  });
  return subsonicPayload(await response.text());
}

beforeAll(async () => {
  resetDatabase(db);
  dbOps.updateSettings({ integrations: {}, onboardingComplete: true });
  userOps.createUser("alice", hashPassword("password123"), "user");
  userOps.createUser("bob", hashPassword("bob-password"), "user");
  userOps.setApiKey(userOps.getUserByUsername("alice")!.id, "alice-key");
  userOps.setApiKey(userOps.getUserByUsername("bob")!.id, "bob-key");
  aurral = await startServerProcess({ extraEnv: { AURRAL_SUBSONIC_REQUIRE_API_KEY: "true" } });
});

afterAll(async () => {
  await aurral?.stop();
  await cleanupIsolatedState(isolatedState);
});

test("requires the signed-in user's own API key when AURRAL_SUBSONIC_REQUIRE_API_KEY is on", async () => {
  for (const [headers, status] of [
    [{}, "failed"],
    [{ "X-Api-Key": "wrong" }, "failed"],
    [{ "X-Api-Key": "bob-key" }, "failed"],
    [{ "X-Api-Key": "alice-key" }, "ok"],
  ] as const) {
    const payload = await ping(headers);
    assert.equal(payload.status, status, JSON.stringify(headers));
    if (status === "failed") assert.equal(payload.error.code, 50);
  }
});

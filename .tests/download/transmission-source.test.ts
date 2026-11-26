import assert from "node:assert/strict";
import type { ServerResponse } from "node:http";
import { buffer } from "node:stream/consumers";
import { mkdir, mkdtemp, readdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import * as v from "valibot";
import { afterAll, afterEach, beforeEach, test } from "vite-plus/test";
import {
  cleanupIsolatedState,
  createMockHttpServer,
  resetDatabase,
  setupIsolatedBackend,
} from "../helpers/backendTestHarness.ts";
import { writeFlacFixture } from "../helpers/flacFixture.ts";
import { createTrackedJob, trackedJob } from "./fixtures.ts";

const [
  state,
  { db },
  { dbOps },
  { transmissionSource, removeSeededTransmissionTorrents },
  { transmissionClient },
  { DownloadSourceError },
  { processSourcePipelinePayload },
  { downloadTracker },
] = await setupIsolatedBackend(
  "transmission-source",
  () => import("../../backend/config/db-sqlite.ts"),
  () => import("../../backend/db/helpers/index.ts"),
  () => import("../../backend/services/transmissionSource.ts"),
  () => import("../../backend/services/transmissionClient.ts"),
  () => import("../../backend/services/download/downloadSource.ts"),
  () => import("../../backend/services/download/sourcePipeline.ts"),
  () => import("../../backend/services/downloadJobs/downloadTracker.ts"),
);

type Payload = Parameters<typeof processSourcePipelinePayload>[0];
type Torrent = {
  hashString: string;
  status: number;
  percentDone: number;
  error: number;
  errorString: string;
  downloadDir: string;
  isFinished: boolean;
  labels: string[];
  files: { name: string; length: number }[];
  wanted: number[];
};

const MAGNET = "magnet:?xt=urn:btih:abc";
const AUTH = `Basic ${Buffer.from("aurral:secret").toString("base64")}`;
const ALBUM = {
  artist: "Glass Coast",
  title: "Lowlands",
  releaseMbid: null,
  releaseGroupMbid: null,
  tracks: [],
};

const rpcBody = v.object({
  method: v.string(),
  arguments: v.record(v.string(), v.unknown()),
});
type Call = v.InferOutput<typeof rpcBody>;

let fake: Awaited<ReturnType<typeof startFake>>;
let folder = "";

function json(response: ServerResponse, value: unknown, status = 200) {
  response.writeHead(status, { "Content-Type": "application/json" });
  response.end(JSON.stringify(value));
}

const torrent = (hashString: string, fields: Partial<Torrent> = {}): Torrent => ({
  hashString,
  status: 4,
  percentDone: 0,
  error: 0,
  errorString: "",
  downloadDir: "/downloads",
  isFinished: false,
  labels: [],
  files: [],
  wanted: [],
  ...fields,
});

async function startFake() {
  const fake = {
    sid: "first",
    handshakes: 0,
    torrents: [] as Torrent[],
    calls: [] as Call[],
    onAdd: (_args: Record<string, unknown>): Torrent | null => null,
  };
  const ids = (args: Record<string, unknown>) =>
    Array.isArray(args.ids) ? args.ids.map(String) : null;
  const pick = (args: Record<string, unknown>) =>
    fake.torrents.filter((entry) => !ids(args) || ids(args)?.includes(entry.hashString));
  const server = await createMockHttpServer(async (request, response) => {
    const url = new URL(request.url ?? "/", "http://fake");
    if (url.pathname === "/transmission/rpc") {
      if (request.headers.authorization !== AUTH) {
        response.writeHead(401);
        return response.end();
      }
      if (request.headers["x-transmission-session-id"] !== fake.sid) {
        fake.handshakes += 1;
        response.writeHead(409, { "X-Transmission-Session-Id": fake.sid });
        return response.end();
      }
      const call = v.parse(rpcBody, JSON.parse((await buffer(request)).toString()));
      fake.calls.push(call);
      const args = call.arguments;
      if (call.method === "session-get")
        return json(response, { result: "success", arguments: { version: "4.0.6 (38c164933e)" } });
      if (call.method === "torrent-add") {
        const added = fake.onAdd(args);
        if (added) fake.torrents.push(added);
        return json(response, {
          result: "success",
          arguments: added
            ? { "torrent-added": { id: 1, name: "Lowlands", hashString: added.hashString } }
            : { "torrent-duplicate": { id: 1, name: "Lowlands", hashString: "dup" } },
        });
      }
      if (call.method === "torrent-get")
        return json(response, { result: "success", arguments: { torrents: pick(args) } });
      if (call.method === "torrent-set") {
        for (const entry of pick(args)) {
          if (Array.isArray(args.labels)) entry.labels = args.labels.map(String);
          if (Array.isArray(args["files-unwanted"]))
            for (const index of args["files-unwanted"]) entry.wanted[Number(index)] = 0;
        }
        return json(response, { result: "success", arguments: {} });
      }
      if (call.method === "torrent-start") {
        for (const entry of pick(args)) Object.assign(entry, { status: 6, percentDone: 1 });
        return json(response, { result: "success", arguments: {} });
      }
      if (call.method === "torrent-remove") {
        const removed = new Set(ids(args));
        fake.torrents = fake.torrents.filter((entry) => !removed.has(entry.hashString));
        return json(response, { result: "success", arguments: {} });
      }
      return json(response, { result: "method name not recognized", arguments: {} });
    }
    if (url.pathname === "/release.torrent") return response.end("d4:infod4:name8:Lowlandsee");
    if (url.pathname === "/magnet-only") {
      response.writeHead(302, { Location: MAGNET });
      return response.end();
    }
    if (url.pathname === "/api/v1/indexer")
      return json(response, [
        {
          id: 3,
          name: "Torrents",
          enable: true,
          protocol: "torrent",
          supportsSearch: true,
          capabilities: {
            categories: [{ id: 3000 }],
            musicSearchParams: ["q", "artist", "album"],
          },
        },
      ]);
    if (url.pathname === "/api/v1/search") return json(response, releases);
    response.writeHead(404);
    response.end();
  });
  return Object.assign(fake, { url: server.url, close: server.close });
}

let releases: Record<string, unknown>[] = [];

function release(guid: string, title: string, link = "/release.torrent", field = "downloadUrl") {
  return {
    guid,
    title,
    seeders: 5,
    size: 300_000_000,
    protocol: "torrent",
    indexerId: 3,
    [field]: `${fake.url}${link}`,
    categories: [{ id: 3000 }],
  };
}

function useSettings(transmission: Record<string, unknown> = {}) {
  const settings = dbOps.getSettings();
  dbOps.updateSettings({
    ...settings,
    integrations: {
      ...settings.integrations,
      prowlarr: { enabled: true, url: fake.url, apiKey: "prowlarr-key" },
      transmission: {
        enabled: true,
        url: fake.url,
        username: "aurral",
        password: "secret",
        label: "aurral",
        ...transmission,
      },
    },
    pathMappings: [{ source: "transmission", remote: "/downloads", local: folder }],
  });
  transmissionClient.updateConfig();
}

beforeEach(async () => {
  resetDatabase(db);
  fake = await startFake();
  releases = [];
  folder = await mkdtemp(path.join(tmpdir(), "aurral-transmission-"));
  useSettings();
});

afterEach(async () => {
  await fake.close();
  await rm(folder, { recursive: true, force: true });
});

afterAll(async () => {
  await cleanupIsolatedState(state);
});

const search = () =>
  transmissionSource.albums.search(ALBUM, {
    upgrade: null,
    track: null,
    denied: new Set(),
    release: null,
  });

test("a download adds a torrent file paused with Aurral's label and folder, a magnet running, and moves on when Transmission already has it", async () => {
  useSettings({ directory: "/data/Downloads/aurral" });
  releases = [
    release("file", "Glass Coast - Lowlands [FLAC]"),
    release("magnet", "Glass Coast - Lowlands [MP3]", "/magnet-only", "magnetUrl"),
  ];
  const [file, magnet] = await search();
  assert.ok(file && magnet);
  let added = 0;
  fake.onAdd = () => torrent(`hash-${(added += 1)}`);
  const input = { album: ALBUM, directory: folder, signal: new AbortController().signal };

  assert.deepEqual(await transmissionSource.albums.download({ ...input, result: file }), {
    pending: "hash-1",
  });
  assert.deepEqual(await transmissionSource.albums.download({ ...input, result: magnet }), {
    pending: "hash-2",
  });
  const [fileAdd, magnetAdd] = fake.calls.filter((call) => call.method === "torrent-add");
  assert.deepEqual(fileAdd?.arguments, {
    metainfo: Buffer.from("d4:infod4:name8:Lowlandsee").toString("base64"),
    paused: true,
    labels: ["aurral"],
    "download-dir": "/data/Downloads/aurral",
  });
  assert.deepEqual(magnetAdd?.arguments, {
    filename: MAGNET,
    paused: false,
    labels: ["aurral"],
    "download-dir": "/data/Downloads/aurral",
  });

  fake.onAdd = () => null;
  await assert.rejects(
    transmissionSource.albums.download({ ...input, result: magnet }),
    (error) => error instanceof DownloadSourceError && /didn't add/.test(error.message),
  );
});

test("status takes a new session id, waits for the torrent, links the kept audio files, and labels it imported; a connection test checks the password", async () => {
  await mkdir(path.join(folder, "Lowlands", "CD2"), { recursive: true });
  await writeFile(path.join(folder, "Lowlands", "01 Night Drive.flac"), "audio");
  await writeFile(path.join(folder, "Lowlands", "CD2", "01 Tide.flac"), "audio");
  const directory = path.join(folder, "staging");
  await mkdir(directory);
  const lowlands = torrent("h1", {
    percentDone: 0.5,
    labels: ["aurral"],
    files: [
      { name: "Lowlands/01 Night Drive.flac", length: 5 },
      { name: "Lowlands/CD2/01 Tide.flac", length: 5 },
      { name: "Lowlands/02 Skipped.flac", length: 5 },
      { name: "Lowlands/cover.jpg", length: 5 },
    ],
    wanted: [1, 1, 0, 1],
  });
  fake.torrents = [torrent("other", { percentDone: 1 }), lowlands];

  assert.deepEqual(await transmissionSource.status({ id: "h1", directory }), { done: false });
  fake.sid = "second";
  Object.assign(lowlands, { percentDone: 1, status: 2 });
  assert.deepEqual(await transmissionSource.status({ id: "h1", directory }), { done: false });
  lowlands.status = 6;
  assert.deepEqual(await transmissionSource.status({ id: "h1", directory }), { done: true });
  assert.deepEqual(lowlands.labels, ["aurral", "aurral-imported"]);
  assert.equal(fake.handshakes, 2);
  assert.deepEqual((await readdir(directory, { recursive: true })).sort(), [
    "Lowlands",
    path.join("Lowlands", "01 Night Drive.flac"),
    path.join("Lowlands", "CD2"),
    path.join("Lowlands", "CD2", "01 Tide.flac"),
  ]);

  Object.assign(lowlands, {
    error: 3,
    errorString: "No data found! Ensure your drives are connected",
  });
  await assert.rejects(
    transmissionSource.status({ id: "h1", directory }),
    (error) => error instanceof DownloadSourceError && /No data found/.test(error.message),
  );
  await assert.rejects(
    transmissionSource.status({ id: "gone", directory }),
    (error) => error instanceof DownloadSourceError && /no longer has/.test(error.message),
  );

  const connected = await transmissionClient.testConnection();
  assert.equal(connected.connected, true);
  assert.equal(connected.message, "Transmission 4.0.6 is connected");
  useSettings({ password: "wrong" });
  const refused = await transmissionClient.testConnection();
  assert.equal(refused.connected, false);
  assert.match(refused.message, /username or password/);
});

test("discard removes the torrent and its files; cleanup removes only imported torrents Transmission stopped at their seeding limit", async () => {
  const finished = { percentDone: 1, status: 0, isFinished: true };
  fake.torrents = [
    torrent("partial", { percentDone: 0.4 }),
    torrent("seeded", { ...finished, labels: ["aurral", "aurral-imported"] }),
    torrent("seeding", { percentDone: 1, status: 6, labels: ["aurral-imported"] }),
    torrent("paused", { percentDone: 1, status: 0, labels: ["aurral-imported"] }),
    torrent("unimported", { ...finished, labels: ["aurral"] }),
    torrent("sonarr", { ...finished, labels: ["tv-sonarr"] }),
  ];

  await transmissionSource.discard("partial");
  await removeSeededTransmissionTorrents();

  const removals = fake.calls.filter((call) => call.method === "torrent-remove");
  assert.deepEqual(
    removals.map((call) => call.arguments),
    [
      { ids: ["partial"], "delete-local-data": true },
      { ids: ["seeded"], "delete-local-data": true },
    ],
  );
});

async function runPipeline(start: Payload) {
  const fail = async (_payload: Payload, _job: unknown, message: string) => {
    throw new Error(message);
  };
  let payload: Payload | null = start;
  const phases: string[] = [];
  while (payload && payload.phase !== "done") {
    phases.push(payload.phase);
    const next: unknown = await processSourcePipelinePayload(payload, transmissionSource, {
      failOrTryNextSource: fail,
    });
    payload = next && typeof next === "object" && "phase" in next ? (next as Payload) : null;
  }
  return phases;
}

test("a track request downloads only the track's file from a paused album torrent, starts it, and files the track", async () => {
  const album = path.join(folder, "Glass Coast - Lowlands");
  await mkdir(album);
  const titles = ["Tide", "Night Drive"];
  for (const [index, title] of titles.entries())
    await writeFlacFixture(
      path.join(album, `0${index + 1} ${title}.flac`),
      { title, artist: "Glass Coast", album: "Lowlands", track: index + 1 },
      { seconds: 1 },
    );
  releases = [release("lowlands", "Glass Coast - Lowlands (2020) [FLAC]")];
  fake.onAdd = () =>
    torrent("h1", {
      status: 0,
      files: titles.map((title, index) => ({
        name: `Glass Coast - Lowlands/0${index + 1} ${title}.flac`,
        length: 30_000_000,
      })),
      wanted: [1, 1],
    });
  const jobId = createTrackedJob(
    downloadTracker,
    {
      artistName: "Glass Coast",
      trackName: "Night Drive",
      albumName: "Lowlands",
      durationMs: 1000,
    },
    "transmission-source",
  );

  assert.deepEqual(await runPipeline({ jobId, phase: "search", source: "transmission" }), [
    "search",
    "download",
    "poll",
    "finalize",
  ]);
  assert.deepEqual(fake.torrents[0]?.wanted, [0, 1]);
  assert.ok(fake.calls.some((call) => call.method === "torrent-start"));
  const job = trackedJob(downloadTracker, jobId);
  assert.equal(job.status, "done");
  assert.equal(job.releaseGuid, "album:lowlands");
  assert.ok(!fake.calls.some((call) => call.method === "torrent-remove"));
});

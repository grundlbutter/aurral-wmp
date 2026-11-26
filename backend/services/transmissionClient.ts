import * as v from "valibot";
import { http } from "../../lib/http.ts";
import { asAppError } from "../../lib/errors.ts";
import { dbOps } from "../db/helpers/index.ts";
import { readMany, readOne } from "./integrationData.ts";
import { prowlarrClient } from "./prowlarrClient.ts";
import type { FetchedTorrent } from "./torrentSource.ts";
import { normalizeBaseUrl } from "./usenetClientCommon.ts";

export const transmissionSettings = Object.freeze({
  key: "transmission",
  label: "Transmission",
  subtitle: "Torrents",
  enabledDefault: false,
  testRequiresEnabled: true,
  fields: Object.freeze([
    Object.freeze({ key: "enabled", label: "Enable Transmission", type: "toggle" }),
    Object.freeze({
      key: "url",
      label: "Server URL",
      type: "url",
      required: true,
      section: "Connection",
      placeholder: "http://localhost:9091",
    }),
    Object.freeze({ key: "username", label: "Username", type: "text", section: "Connection" }),
    Object.freeze({
      key: "password",
      label: "Password",
      type: "password",
      secret: true,
      section: "Connection",
    }),
    Object.freeze({ key: "label", label: "Label", type: "text", section: "Downloads" }),
    Object.freeze({
      key: "directory",
      label: "Download folder",
      type: "text",
      section: "Downloads",
      placeholder: "/downloads/aurral",
      hint: "As Transmission sees it. Empty uses Transmission's own.",
    }),
    Object.freeze({
      key: "priority",
      label: "Track priority",
      type: "number",
      min: 1,
      max: 1000,
      advanced: true,
    }),
    Object.freeze({
      key: "albumPriority",
      label: "Album priority",
      type: "number",
      min: 1,
      max: 1000,
      advanced: true,
      hint: "Empty uses track priority.",
    }),
  ]),
  defaults: Object.freeze({
    enabled: false,
    url: "",
    username: "",
    password: "",
    label: "aurral",
    directory: "",
    priority: 30,
  }),
  validation: Object.freeze({ required: ["url"], url: ["url"] }),
  testConnection: true,
});

type TransmissionConfig = Record<string, unknown> | null;

// The URL is the server, like http://transmission:9091, or its full RPC path
// when a reverse proxy moves it.
function rpcUrl(url: string) {
  return /\/rpc$/u.test(url) ? url : `${url}/transmission/rpc`;
}

function getSettings(config: TransmissionConfig) {
  const transmission = config || dbOps.getSettings()?.integrations?.transmission || {};
  return {
    enabled: transmission.enabled === true,
    url: normalizeBaseUrl(transmission.url),
    username: String(transmission.username || "").trim(),
    password: String(transmission.password || ""),
    label: String(transmission.label ?? "aurral").trim(),
    directory: String(transmission.directory || "").trim(),
  };
}

const responseSchema = v.looseObject({
  result: v.string(),
  arguments: v.optional(v.record(v.string(), v.unknown()), {}),
});

const addedSchema = v.looseObject({ hashString: v.string() });

const torrentSchema = v.looseObject({
  hashString: v.string(),
  status: v.optional(v.number(), 0),
  percentDone: v.optional(v.number(), 0),
  metadataPercentComplete: v.optional(v.number(), 1),
  error: v.optional(v.number(), 0),
  errorString: v.optional(v.string(), ""),
  downloadDir: v.optional(v.string(), ""),
  isFinished: v.optional(v.boolean(), false),
  labels: v.optional(v.array(v.string()), []),
  files: v.optional(v.array(v.looseObject({ name: v.string(), length: v.number() })), []),
  wanted: v.optional(v.array(v.union([v.boolean(), v.number()])), []),
});

const FIELDS = [
  "hashString",
  "status",
  "percentDone",
  "metadataPercentComplete",
  "error",
  "errorString",
  "downloadDir",
  "isFinished",
  "labels",
];

// Transmission hands out a session id with a 409 and expects it back. Each
// download worker learns it again with one extra request.
const sessions = new Map<string, string>();

export class TransmissionClient {
  key = "transmission" as const;
  name = "Transmission" as const;
  _config: TransmissionConfig = null;

  updateConfig(config: TransmissionConfig = null) {
    this._config = config;
  }

  _getSettings() {
    return getSettings(this._config);
  }

  isConfigured() {
    const { enabled, url } = this._getSettings();
    return enabled && Boolean(url);
  }

  getStatus() {
    const { enabled } = this._getSettings();
    return { enabled, configured: this.isConfigured() };
  }

  async _rpc(method: string, args: Record<string, unknown> = {}) {
    const settings = this._getSettings();
    const url = rpcUrl(settings.url);
    for (let attempt = 0; attempt < 2; attempt += 1) {
      const session = sessions.get(url);
      const response = await http.post(
        url,
        { method, arguments: args },
        {
          headers: session ? { "X-Transmission-Session-Id": session } : {},
          ...(settings.username && {
            basicAuth: { username: settings.username, password: settings.password },
          }),
          timeoutMs: 30000,
          okStatus: () => true,
        },
      );
      if (response.status === 409) {
        sessions.set(url, response.headers["x-transmission-session-id"] ?? "");
        continue;
      }
      if (response.status === 401)
        throw new Error("Transmission rejected the username or password");
      if (response.status === 403)
        throw new Error("Transmission refused Aurral's address. Add it to rpc-whitelist.");
      if (response.status >= 400)
        throw new Error(`Transmission answered ${method} with HTTP ${response.status}`);
      const body = readOne(responseSchema, response.data, {
        service: "Transmission",
        endpoint: method,
      });
      if (body.result !== "success")
        throw new Error(`Transmission refused ${method}: ${body.result}`);
      return body.arguments;
    }
    throw new Error("Transmission didn't accept its own session id");
  }

  async testConnection() {
    const settings = this._getSettings();
    if (!settings.enabled)
      return {
        ok: false,
        configured: false,
        connected: false,
        message: "Transmission is disabled",
      };
    if (!settings.url)
      return {
        ok: false,
        configured: false,
        connected: false,
        message: "Transmission URL is required",
      };
    try {
      const session = await this._rpc("session-get", { fields: ["version"] });
      const version = typeof session.version === "string" ? session.version.split(" ")[0] : "";
      const connected = `Transmission ${version} is connected`.replace(/\s+/gu, " ");
      if (prowlarrClient.isConfigured())
        return { ok: true, configured: true, connected: true, version, message: connected };
      return {
        ok: true,
        warning: true,
        configured: true,
        connected: true,
        version,
        message: `${connected}. Set up Prowlarr so Aurral can search for torrents.`,
      };
    } catch (error) {
      return {
        ok: false,
        configured: true,
        connected: false,
        message: asAppError(error)?.message || "Failed to reach Transmission",
      };
    }
  }

  // A torrent file waits paused until Aurral picks its files. A magnet has to
  // run to fetch its file list, so it may start on files Aurral then skips.
  // A torrent Transmission already has is not Aurral's, so it isn't added.
  async addTorrent(torrent: FetchedTorrent) {
    const { label, directory } = this._getSettings();
    const added = await this._rpc("torrent-add", {
      ...("magnet" in torrent
        ? { filename: torrent.magnet, paused: false }
        : { metainfo: torrent.file.toString("base64"), paused: true }),
      ...(label && { labels: [label] }),
      ...(directory && { "download-dir": directory }),
    });
    if (!added["torrent-added"]) return null;
    return readOne(addedSchema, added["torrent-added"], {
      service: "Transmission",
      endpoint: "torrent-add",
    }).hashString;
  }

  async torrents(hashes: string[] | null, { withFiles = false } = {}) {
    const result = await this._rpc("torrent-get", {
      fields: withFiles ? [...FIELDS, "files", "wanted"] : FIELDS,
      ...(hashes && { ids: hashes }),
    });
    return readMany(torrentSchema, result.torrents, {
      service: "Transmission",
      endpoint: "torrent-get",
    }).filter((torrent) => !hashes || hashes.includes(torrent.hashString));
  }

  async torrentFiles(hash: string) {
    const [torrent] = await this.torrents([hash], { withFiles: true });
    return (torrent?.files ?? []).map((file, index) => ({
      index,
      name: file.name,
      size: file.length,
      wanted: Boolean(torrent?.wanted[index] ?? true),
    }));
  }

  async skipFiles(hash: string, indexes: number[]) {
    if (indexes.length === 0) return;
    await this._rpc("torrent-set", { ids: [hash], "files-unwanted": indexes });
  }

  async startTorrent(hash: string) {
    await this._rpc("torrent-start", { ids: [hash] });
  }

  // Transmission replaces a torrent's labels, so the new one joins the old.
  async addLabel(hash: string, label: string) {
    const [torrent] = await this.torrents([hash]);
    if (!torrent || torrent.labels.includes(label)) return;
    await this._rpc("torrent-set", { ids: [hash], labels: [...torrent.labels, label] });
  }

  async removeTorrents(hashes: string[]) {
    if (hashes.length === 0) return;
    await this._rpc("torrent-remove", { ids: hashes, "delete-local-data": true });
  }
}

export const transmissionClient = new TransmissionClient();

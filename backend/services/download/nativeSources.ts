import { isOwnKey } from "../../../lib/ownKey.ts";
import { deemixSource } from "../deemixSource.ts";
import { pluginDownloadSource } from "../plugins/pluginDownloadSource.ts";
import { qbittorrentSource } from "../qbittorrentSource.ts";
import { transmissionSource } from "../transmissionSource.ts";
import { tidarrSource, usenetSource } from "../usenetSource.ts";
import { ytdlpSource } from "../ytdlpSource.ts";
import type { DownloadSource, NATIVE_SOURCE_IDS } from "./downloadSource.ts";

const sources: Record<(typeof NATIVE_SOURCE_IDS)[number], DownloadSource> = {
  deemix: deemixSource,
  qbittorrent: qbittorrentSource,
  tidarr: tidarrSource,
  transmission: transmissionSource,
  usenet: usenetSource,
  ytdlp: ytdlpSource,
};

export const nativeDownloadSource = (id: string | null | undefined) =>
  id && isOwnKey(sources, id) ? sources[id] : null;

export const contractSource = (id: string | null | undefined) =>
  nativeDownloadSource(id) ?? pluginDownloadSource(id);

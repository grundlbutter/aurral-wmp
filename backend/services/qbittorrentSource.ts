import { randomUUID } from "node:crypto";
import { qbittorrentClient } from "./qbittorrentClient.ts";
import { IMPORTED_TAG, torrentSource } from "./torrentSource.ts";

const BUSY_STATES = new Set(["checkingUP", "checkingDL", "checkingResumeData", "moving", "metaDL"]);
const FAILED_STATES = new Set(["error", "missingFiles"]);
const STOPPED_STATES = new Set(["stoppedUP", "pausedUP"]);

export const qbittorrentSource = torrentSource("qbittorrent", {
  name: "qBittorrent",
  async add(torrent) {
    const tag = `aurral-${randomUUID()}`;
    return (await qbittorrentClient.addTorrent(torrent, tag)) ? tag : null;
  },
  async find(tag) {
    return (await qbittorrentClient.torrentsTagged(tag)).map((torrent) => ({
      hash: torrent.hash,
      savePath: torrent.save_path,
      done: torrent.progress >= 1 && !BUSY_STATES.has(torrent.state),
      failure: FAILED_STATES.has(torrent.state)
        ? `qBittorrent stopped the torrent: ${torrent.state}`
        : null,
    }));
  },
  async files(hash) {
    return (await qbittorrentClient.torrentFiles(hash)).map((file) => ({
      index: file.index,
      name: file.name,
      size: file.size,
      wanted: file.priority > 0,
    }));
  },
  // A magnet stops once qBittorrent has its files, so nothing downloads
  // before Aurral keeps the ones it wants.
  async keep(hash, skipped) {
    await qbittorrentClient.skipFiles(hash, skipped);
    await qbittorrentClient.startTorrent(hash);
  },
  markImported: (hash) => qbittorrentClient.addTag(hash, IMPORTED_TAG),
  remove: (hashes) => qbittorrentClient.deleteTorrents(hashes),
});

// As in Lidarr, qBittorrent stops a torrent at its seeding limit, and Aurral
// then removes it. Only a torrent Aurral took its files from is marked, so a
// download still in progress is never removed.
export async function removeSeededTorrents() {
  if (!qbittorrentClient.isConfigured()) return;
  const torrents = await qbittorrentClient.torrentsTagged(IMPORTED_TAG);
  await qbittorrentClient.deleteTorrents(
    torrents
      .filter((torrent) => torrent.progress >= 1 && STOPPED_STATES.has(torrent.state))
      .map((torrent) => torrent.hash),
  );
}

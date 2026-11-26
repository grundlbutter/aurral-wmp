import { transmissionClient } from "./transmissionClient.ts";
import { IMPORTED_TAG, torrentSource } from "./torrentSource.ts";

// Transmission's torrent statuses.
const STOPPED = 0;
const CHECKING = new Set([1, 2]);
const LOCAL_ERROR = 3;

// A download is known by its torrent's hash, which Transmission returns when
// it adds the torrent.
export const transmissionSource = torrentSource("transmission", {
  name: "Transmission",
  add: (torrent) => transmissionClient.addTorrent(torrent),
  async find(hash) {
    return (await transmissionClient.torrents([hash])).map((torrent) => ({
      hash: torrent.hashString,
      savePath: torrent.downloadDir,
      done:
        torrent.percentDone >= 1 &&
        torrent.metadataPercentComplete >= 1 &&
        !CHECKING.has(torrent.status),
      failure:
        torrent.error === LOCAL_ERROR
          ? `Transmission stopped the torrent: ${torrent.errorString || "local error"}`
          : null,
    }));
  },
  files: (hash) => transmissionClient.torrentFiles(hash),
  async keep(hash, skipped) {
    await transmissionClient.skipFiles(hash, skipped);
    await transmissionClient.startTorrent(hash);
  },
  markImported: (hash) => transmissionClient.addLabel(hash, IMPORTED_TAG),
  remove: (hashes) => transmissionClient.removeTorrents(hashes),
});

// Like Sonarr, Aurral removes a torrent once Transmission stops it at its
// seed ratio or idle limit. Only a torrent Aurral took its files from carries
// the label, so a download still in progress, or one of another app's, is
// never removed.
export async function removeSeededTransmissionTorrents() {
  if (!transmissionClient.isConfigured()) return;
  const torrents = await transmissionClient.torrents(null);
  await transmissionClient.removeTorrents(
    torrents
      .filter(
        (torrent) =>
          torrent.labels.includes(IMPORTED_TAG) && torrent.isFinished && torrent.status === STOPPED,
      )
      .map((torrent) => torrent.hashString),
  );
}

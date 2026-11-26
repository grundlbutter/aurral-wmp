import fs from "node:fs/promises";
import path from "node:path";
import * as v from "valibot";
import { http } from "../../lib/http.ts";
import { asAppError } from "../../lib/errors.ts";
import {
  DownloadSourceError,
  type AlbumQuery,
  type DownloadSource,
  type Upgrade,
} from "./download/downloadSource.ts";
import {
  buildUsenetSearchQueries,
  isAudioFile,
  isProwlarrMusicQuery,
  rankUsenetReleases,
} from "./downloadJobs/usenetReleaseSearch.ts";
import { getPathMappings, resolveLocalPath } from "./pathMappings.ts";
import { prowlarrClient } from "./prowlarrClient.ts";
import { orderAdvertisedQualityCandidates } from "./qualityProfileModel.ts";
import { getQualityProfile } from "./qualityProfile.ts";

export type FetchedTorrent = { magnet: string } | { file: Buffer };
export type Torrent = { hash: string; savePath: string; done: boolean; failure: string | null };
export type TorrentFile = { index: number; name: string; size: number; wanted: boolean };

// What a torrent client does for Aurral. A download is known by the id `add`
// returns, and `find` lists the torrents behind it.
export type TorrentClient = {
  name: string;
  add: (torrent: FetchedTorrent) => Promise<string | null>;
  find: (id: string) => Promise<Torrent[]>;
  files: (hash: string) => Promise<TorrentFile[]>;
  keep: (hash: string, skipped: number[]) => Promise<void>;
  markImported: (hash: string) => Promise<void>;
  remove: (hashes: string[]) => Promise<void>;
};

const releaseData = v.object({
  downloadUrl: v.nullish(v.string()),
  magnetUrl: v.nullish(v.string()),
});

export const IMPORTED_TAG = "aurral-imported";

const failure = (error: unknown) => asAppError(error)?.message || String(error);

async function searchReleases(album: AlbumQuery, upgrade: Upgrade) {
  const indexers = await prowlarrClient.getEnabledTorrentIndexers();
  if (indexers.length === 0)
    throw new DownloadSourceError("Prowlarr has no torrent indexers turned on");
  const musicIndexers = indexers.filter((indexer) => indexer.musicSearch);
  const context = {
    artistName: album.artist,
    albumName: album.title,
    releaseTracks: album.tracks,
  };
  const queries = buildUsenetSearchQueries(context, {
    musicSearch: musicIndexers.length > 0,
    albumGrab: true,
  });
  const errors: unknown[] = [];
  for (const query of queries) {
    const releases = await prowlarrClient
      .search(
        query,
        isProwlarrMusicQuery(query)
          ? { protocol: "torrent", type: "music", indexers: musicIndexers }
          : { protocol: "torrent", indexers },
      )
      .catch((error: unknown) => {
        errors.push(error);
        return [];
      });
    const ranked = rankUsenetReleases(
      releases
        .filter((release) => release.seeders !== 0)
        .map((release) => ({
          ...release,
          downloadUrl: release.downloadUrl || release.magnetUrl || "",
        })),
      context,
      { protocol: "torrent" },
    ).filter((entry) => entry.releaseAdmissible && entry.resolvedAlbumName);
    const ordered = orderAdvertisedQualityCandidates(ranked, {
      profile: getQualityProfile(),
      currentTier: upgrade?.currentTier ?? null,
      upgrade: upgrade !== null,
      readName: (entry) => entry.raw.release.title,
    });
    if (ordered.length > 0) return ordered;
  }
  if (errors.length > 0 && errors.length === queries.length) throw errors.at(-1);
  return [];
}

// Like Lidarr, Aurral fetches the torrent itself, so the client never needs
// to reach Prowlarr. Prowlarr links to a magnet through a redirect.
async function fetchTorrent(data: v.InferOutput<typeof releaseData>): Promise<FetchedTorrent> {
  const link = data.downloadUrl || data.magnetUrl || "";
  if (link.startsWith("magnet:")) return { magnet: link };
  const response = await http.get(link, {
    responseType: "buffer",
    timeoutMs: 60000,
    okStatus: () => true,
  });
  const location = response.headers.location ?? "";
  if (location.startsWith("magnet:")) return { magnet: location };
  if (response.status !== 200 || !Buffer.isBuffer(response.data))
    throw new DownloadSourceError(`The indexer didn't return the torrent: HTTP ${response.status}`);
  return { file: response.data };
}

// A torrent-backed download source. The finished download stays in the client
// to seed. Aurral links the audio files it kept into the folder it gave the
// download, or copies them across disks.
export function torrentSource(id: "qbittorrent" | "transmission", client: TorrentClient) {
  async function found(pending: string) {
    const [torrent] = await client.find(pending);
    if (!torrent) throw new DownloadSourceError(`${client.name} no longer has the torrent`);
    return torrent;
  }

  async function linkAudioFiles(torrent: Torrent, directory: string) {
    const files = (await client.files(torrent.hash)).filter(
      (file) =>
        file.wanted &&
        isAudioFile(file.name) &&
        !path.relative(directory, path.join(directory, file.name)).startsWith(".."),
    );
    if (files.length === 0) throw new DownloadSourceError("The torrent has no audio files");
    const mappings = getPathMappings(id);
    for (const file of files) {
      const from = resolveLocalPath(`${torrent.savePath}/${file.name}`, mappings);
      const target = path.join(directory, file.name);
      await fs.mkdir(path.dirname(target), { recursive: true });
      await fs
        .link(from, target)
        .catch(() => fs.copyFile(from, target))
        .catch(() => {
          throw new DownloadSourceError(
            `Aurral can't find ${from}. Add a remote path mapping for ${client.name}.`,
          );
        });
    }
  }

  return {
    id,
    staging: id,
    albums: {
      async search(album, { upgrade }) {
        const ranked = await searchReleases(album, upgrade).catch((error: unknown) => {
          if (error instanceof DownloadSourceError) throw error;
          throw new DownloadSourceError(`Prowlarr search failed: ${failure(error)}`);
        });
        return ranked.map(({ raw, resolvedAlbumName }) => ({
          id: raw.release.guid || String(raw.downloadUrl),
          title: String(resolvedAlbumName),
          artist: album.artist || raw.release.title,
          data: { downloadUrl: raw.release.downloadUrl, magnetUrl: raw.release.magnetUrl },
        }));
      },
      async download({ result }) {
        const pending = await client.add(await fetchTorrent(v.parse(releaseData, result.data)));
        if (!pending) throw new DownloadSourceError(`${client.name} didn't add the torrent`);
        return { pending };
      },
      files: {
        async list(pending) {
          const [torrent] = await client.find(pending);
          const files = torrent ? await client.files(torrent.hash) : [];
          return files.length > 0 ? files.map(({ name, size }) => ({ path: name, size })) : null;
        },
        async keep({ id: pending, paths }) {
          const torrent = await found(pending);
          const files = await client.files(torrent.hash);
          await client.keep(
            torrent.hash,
            files.filter((file) => !paths.includes(file.name)).map((file) => file.index),
          );
        },
      },
    },
    async status({ id: pending, directory }) {
      const torrent = await found(pending);
      if (torrent.failure) throw new DownloadSourceError(torrent.failure);
      if (!torrent.done) return { done: false };
      await linkAudioFiles(torrent, directory);
      await client.markImported(torrent.hash);
      return { done: true };
    },
    async discard(pending) {
      const torrents = await client.find(pending);
      await client.remove(torrents.map((torrent) => torrent.hash));
    },
  } satisfies DownloadSource;
}

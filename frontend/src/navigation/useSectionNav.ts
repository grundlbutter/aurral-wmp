import { useMemo } from "react";
import { useLocation } from "react-router";
import { useAuth } from "../contexts/AuthContext";
import { ACTIVITY_VIEWS, buildActivityPath, WANTED_VIEWS } from "./activityNavConfig";
import { LIBRARY_VIEWS } from "./libraryNavConfig";
import { lastRecommendedPath, RECOMMENDED_PATH } from "./recommendedPath";
import { DEFAULT_SHOWS_FILTER, SHOWS_FILTERS } from "./showsNavConfig";
import { resolveSection, type SectionId } from "./sectionParent.ts";

function firstSegment(pathname: string, prefix: string) {
  return pathname.slice(prefix.length).replace(/^\//, "").split("/")[0];
}

type SectionNavContext = {
  here: string | null;
  search: string;
  allowed: (permission: string | undefined) => boolean;
  newsConfigured: boolean;
};

function buildSectionNav(
  id: SectionId,
  { here, search, allowed, newsConfigured }: SectionNavContext,
) {
  if (id === "discover") {
    const path = here ?? "";
    const items = [
      { id: "home", label: "For you", path: "/", active: path === "/" },
      {
        id: "playlists",
        label: "Playlists",
        path: "/discover/playlists",
        active: path.startsWith("/discover/playlists"),
      },
      {
        id: "recommended",
        label: "Recommended",
        path: lastRecommendedPath(path),
        active: path.startsWith(RECOMMENDED_PATH),
      },
      {
        id: "trending",
        label: "Trending",
        path: "/discover/trending",
        active: path.startsWith("/discover/trending"),
      },
    ];
    if (newsConfigured) {
      items.push({
        id: "news",
        label: "News",
        path: "/discover/news",
        active: path === "/discover/news",
      });
    }
    return { id, title: "Discover", label: "Discover views", items };
  }

  if (id === "library") {
    const segment = here === null ? undefined : firstSegment(here, "/library");
    const items = [
      { id: "home", label: "Overview", path: "/library", active: segment === "" },
      ...LIBRARY_VIEWS.filter((view) => allowed(view.permission)).map((view) => ({
        id: view.id,
        label: view.label,
        path: view.path,
        active: segment === view.id,
      })),
    ];
    return { id, title: "Library", label: "Library views", items };
  }

  if (id === "activity") {
    const segment = here === null ? undefined : firstSegment(here, "/activity");
    const wantedTab = new URLSearchParams(search).get("tab") === "cutoff" ? "cutoff" : "missing";
    const items = ACTIVITY_VIEWS.filter((view) => view.id !== "missing").map((view) => ({
      id: view.id,
      label: view.label,
      path: buildActivityPath(view.id),
      active: segment === view.id,
    }));
    if (allowed("accessPlaylists")) {
      items.push(
        ...WANTED_VIEWS.map((view) => ({
          id: `wanted-${view.id}`,
          label: view.label,
          path: view.path,
          active: segment === "missing" && wantedTab === view.id,
        })),
      );
    }
    return { id, title: "Activity", label: "Activity views", items };
  }

  const segment = here === null ? undefined : firstSegment(here, "/shows") || DEFAULT_SHOWS_FILTER;
  const items = SHOWS_FILTERS.map((filter) => ({
    id: filter.id,
    label: filter.label,
    path: `/shows/${filter.id}`,
    active: segment === filter.id,
  }));
  return { id, title: "Shows", label: "Show filters", items };
}

export function useSectionNavs() {
  const { key, pathname, search } = useLocation();
  const { user, bootstrap } = useAuth();

  return useMemo(() => {
    const section = resolveSection(key, pathname);
    const context = {
      search,
      newsConfigured: !!bootstrap?.newsConfigured,
      allowed: (permission: string | undefined) =>
        !permission || user?.role === "admin" || !!user?.permissions?.[permission],
    };
    const build = (id: SectionId) =>
      buildSectionNav(id, {
        ...context,
        here: section?.id === id && !section.inherited ? pathname : null,
      });
    return {
      section,
      build,
      current: section ? { ...build(section.id), root: !section.inherited } : null,
    };
  }, [bootstrap?.newsConfigured, key, pathname, search, user]);
}

export function useSectionNav() {
  return useSectionNavs().current;
}

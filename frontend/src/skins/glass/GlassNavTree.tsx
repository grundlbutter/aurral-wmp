import { useState, type ReactNode } from "react";
import { useLocation } from "react-router";
import { useSortable } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import RouteLink from "../../components/RouteLink";
import { SortableList } from "../../components/SortableList";
import { LibraryItemMenu } from "../../components/LibraryItemMenu";
import { usePlaylistOrder } from "../../components/SidebarPlaylists";
import { useFriends, useLocalPeople } from "../../components/PlayerFriends";
import { mergeFriendRows } from "../../components/friendRows.ts";
import {
  AlertCircle,
  ArrowUpCircle,
  AudioWaveform,
  Clock,
  Download,
  Heart,
  LayoutGrid,
  List,
  ListMusic,
  Music,
  Newspaper,
  RotateCcw,
  Sparkles,
  Ticket,
  TrendUp,
  User,
  Users,
  type IconComponent,
} from "../../components/icons";
import { useSectionNavs } from "../../navigation/useSectionNav.ts";
import { flowPath, playlistPath } from "../../navigation/playlistPaths.ts";
import type { SectionId } from "../../navigation/sectionParent.ts";
import { applyCustomOrder } from "../../utils/playlistOrder.ts";
import type { PlaylistStatus } from "../../utils/playlistStats.ts";

type NavItem = { path: string; label: string; icon: IconComponent; section?: string };

type Row = {
  id: string;
  label: string;
  path: string;
  icon: IconComponent;
  active: boolean;
  note?: string;
};

const OPEN_KEY = "glassNavOpen";
const DEFAULT_OPEN = ["library", "playlists"];

const VIEW_ICONS: Record<string, IconComponent> = {
  favorites: Heart,
  albums: LayoutGrid,
  tracks: Music,
  "album-artists": User,
  artists: User,
  genres: List,
  playlists: ListMusic,
  recommended: Sparkles,
  trending: TrendUp,
  news: Newspaper,
  queue: Download,
  history: Clock,
  "wanted-missing": AlertCircle,
  "wanted-cutoff": ArrowUpCircle,
};

function readOpen() {
  try {
    const stored: unknown = JSON.parse(localStorage.getItem(OPEN_KEY) ?? "null");
    if (Array.isArray(stored)) return stored.filter((id) => typeof id === "string");
  } catch {}
  return DEFAULT_OPEN;
}

function useOpenGroups() {
  const [open, setOpen] = useState(readOpen);
  const toggle = (id: string) =>
    setOpen((current) => {
      const next = current.includes(id)
        ? current.filter((entry) => entry !== id)
        : [...current, id];
      try {
        localStorage.setItem(OPEN_KEY, JSON.stringify(next));
      } catch {}
      return next;
    });
  return { isOpen: (id: string) => open.includes(id), toggle };
}

function TreeLink({ row }: { row: Row }) {
  const Icon = row.icon;
  return (
    <RouteLink
      to={row.path}
      data-view={row.id}
      className={`sidebar-link sidebar-link--full glass-tree__row${row.active ? " is-active" : ""}`}
      aria-current={row.active ? "page" : undefined}
    >
      <Icon className="sidebar-link__icon" aria-hidden="true" />
      <span className="sidebar-link__label">{row.label}</span>
      {row.note ? <span className="glass-tree__note">{row.note}</span> : null}
    </RouteLink>
  );
}

function SortableRow({ row }: { row: Row }) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: row.id,
  });
  const Icon = row.icon;
  return (
    <li>
      <RouteLink
        ref={setNodeRef}
        to={row.path}
        style={{ transform: CSS.Transform.toString(transform), transition }}
        className={`sidebar-link sidebar-link--full glass-tree__row${row.active ? " is-active" : ""}${isDragging ? " is-dragging" : ""}`}
        aria-current={row.active ? "page" : undefined}
        aria-roledescription={attributes["aria-roledescription"]}
        aria-describedby={attributes["aria-describedby"]}
        draggable={false}
        onDragStart={(event) => event.preventDefault()}
        {...listeners}
      >
        <Icon className="sidebar-link__icon" aria-hidden="true" />
        <span className="sidebar-link__label">{row.label}</span>
      </RouteLink>
    </li>
  );
}

function Twist({ label, open, onToggle }: { label: string; open: boolean; onToggle: () => void }) {
  return (
    <button
      type="button"
      className="glass-tree__twist"
      aria-expanded={open}
      aria-label={`${open ? "Collapse" : "Expand"} ${label}`}
      onClick={onToggle}
    >
      <svg viewBox="0 0 10 10" aria-hidden="true">
        <path d="M3.5 1.5 7.5 5l-4 3.5z" />
      </svg>
    </button>
  );
}

function Group({
  id,
  label,
  icon: Icon,
  path,
  active,
  open,
  onToggle,
  badge,
  empty = false,
  menu,
  children,
}: {
  id: string;
  label: string;
  icon: IconComponent;
  path?: string;
  active: boolean;
  open: boolean;
  onToggle: () => void;
  badge?: ReactNode;
  empty?: boolean;
  menu?: ReactNode;
  children: ReactNode;
}) {
  const head = (
    <>
      <span className="sidebar-link__icon-wrap">
        <Icon className="sidebar-link__icon" aria-hidden="true" />
        {badge}
      </span>
      <span className="sidebar-link__label">{label}</span>
    </>
  );
  return (
    <li className="glass-tree__group" data-nav={id}>
      <div
        className={`glass-tree__head${menu ? " sidebar-playlists__head-row" : ""}`}
        data-library-menu-target={menu ? true : undefined}
      >
        {empty ? (
          <span className="glass-tree__twist" />
        ) : (
          <Twist label={label} open={open} onToggle={onToggle} />
        )}
        {path ? (
          <RouteLink
            to={path}
            className={`sidebar-link sidebar-link--full glass-tree__title${active ? " is-active" : ""}`}
            aria-current={active ? "page" : undefined}
          >
            {head}
          </RouteLink>
        ) : (
          <button
            type="button"
            className={`sidebar-link sidebar-link--full glass-tree__title${active ? " is-active" : ""}`}
            aria-expanded={open}
            onClick={onToggle}
          >
            {head}
          </button>
        )}
        {menu}
      </div>
      {open && !empty ? children : null}
    </li>
  );
}

function FriendRows({ pathname }: { pathname: string }) {
  const friends = useFriends(true, true);
  const people = useLocalPeople(true, true);
  const linked = friends.data?.state === "linked" ? friends.data : null;
  const rows = mergeFriendRows(people.data?.people ?? [], linked?.friends ?? []);
  if (people.isPending) return null;
  if (!rows.length) {
    return (
      <ul className="glass-tree__items">
        <li className="glass-tree__empty">No one yet</li>
      </ul>
    );
  }
  return (
    <ul className="glass-tree__items">
      {rows.map((row) => (
        <li key={row.key}>
          <TreeLink
            row={{
              id: row.key,
              label: row.name,
              path: row.to,
              icon: row.nowPlaying ? Music : User,
              active: pathname === row.to,
              note: row.nowPlaying ? "listening" : undefined,
            }}
          />
        </li>
      ))}
    </ul>
  );
}

export default function GlassNavTree({
  items,
  status,
  hasReviewAlert,
}: {
  items: NavItem[];
  status: PlaylistStatus | undefined;
  hasReviewAlert: boolean;
}) {
  const { pathname } = useLocation();
  const { section, build } = useSectionNavs();
  const { isOpen, toggle } = useOpenGroups();
  const { order, saveOrder } = usePlaylistOrder(!!status);
  const entry = (key: string) => items.find((item) => (item.section ?? item.path.slice(1)) === key);

  const lists = applyCustomOrder(
    [
      ...(status?.staticPlaylists ?? []).map(({ id, name }) => ({
        id,
        label: name,
        path: playlistPath(id),
        icon: ListMusic,
        kind: "playlist",
      })),
      ...(status?.flows ?? []).map(({ id, name }) => ({
        id,
        label: name,
        path: flowPath(id),
        icon: AudioWaveform,
        kind: "flow",
      })),
    ],
    order,
  ).map((row) => ({ ...row, active: pathname === row.path }));
  const listsOf = (kind: string) => lists.filter((row) => row.kind === kind);
  const reorder = (kind: string) => (ids: string[]) =>
    saveOrder([...ids, ...lists.filter((row) => row.kind !== kind).map((row) => row.id)]);

  const views = (id: SectionId): Row[] =>
    build(id)
      .items.filter((view) => view.id !== "home")
      .map((view) => ({ ...view, icon: VIEW_ICONS[view.id] ?? Ticket }));

  const libraryViews = views("library");
  const onPlaylists = libraryViews.some((view) => view.id === "playlists" && view.active);
  const library = entry("library");
  const groups = [
    library && {
      id: "library",
      item: library,
      rows: libraryViews.filter((view) => view.id !== "playlists"),
      own: section?.id === "library" && !onPlaylists,
    },
    status && {
      id: "playlists",
      item: { path: "/library/playlists", label: "Playlists", icon: ListMusic },
      rows: listsOf("playlist"),
      own: onPlaylists,
      sortable: "playlist",
    },
    ...(["discover", "flows", "shows", "activity"] as const).map((id) => {
      const item = entry(id);
      if (!item) return null;
      if (id === "flows") {
        return { id, item, rows: listsOf("flow"), own: pathname === "/flows", sortable: "flow" };
      }
      return { id, item, rows: views(id), own: section?.id === id };
    }),
  ].filter((group) => !!group);

  const onFriend = pathname.startsWith("/people/") || pathname.startsWith("/friends/");
  const rowActive = (id: string, rows: Row[]) =>
    id === "friends" ? onFriend : rows.some((row) => row.active);
  const all = [...groups, { id: "friends", rows: [], own: false }];
  const selected = all.some((group) => isOpen(group.id) && rowActive(group.id, group.rows))
    ? null
    : (all.find((group) => !isOpen(group.id) && rowActive(group.id, group.rows))?.id ??
      all.find((group) => group.own)?.id);

  return (
    <nav className="glass-tree" aria-label="Library and navigation">
      <ul className="glass-tree__groups">
        {groups.map((group) => (
          <Group
            key={group.id}
            id={group.id}
            label={group.item.label}
            icon={group.item.icon}
            path={group.item.path}
            active={selected === group.id}
            open={isOpen(group.id)}
            onToggle={() => toggle(group.id)}
            empty={!group.rows.length}
            menu={
              group.sortable && order.length > 0 ? (
                <LibraryItemMenu
                  label={group.item.label}
                  triggerLabel={`${group.item.label} order`}
                  triggerClassName="native-library-item-menu__trigger sidebar-playlists__menu"
                  items={[
                    {
                      id: "reset",
                      label: "Reset order",
                      icon: RotateCcw,
                      onSelect: () => saveOrder([]),
                    },
                  ]}
                />
              ) : null
            }
            badge={
              group.id === "activity" && hasReviewAlert ? (
                <span
                  className="sidebar-link__activity sidebar-link__activity--review"
                  aria-label="Needs review"
                />
              ) : null
            }
          >
            {group.sortable ? (
              <SortableList
                ids={group.rows.map((row) => row.id)}
                titleOf={(id) => group.rows.find((row) => row.id === id)?.label ?? "Playlist"}
                onReorder={reorder(group.sortable)}
              >
                <ul className="glass-tree__items">
                  {group.rows.map((row) => (
                    <SortableRow key={row.id} row={row} />
                  ))}
                </ul>
              </SortableList>
            ) : (
              <ul className="glass-tree__items">
                {group.rows.map((row) => (
                  <li key={row.id}>
                    <TreeLink row={row} />
                  </li>
                ))}
              </ul>
            )}
          </Group>
        ))}
        <Group
          id="friends"
          label="Friends"
          icon={Users}
          active={selected === "friends"}
          open={isOpen("friends")}
          onToggle={() => toggle("friends")}
        >
          <FriendRows pathname={pathname} />
        </Group>
      </ul>
    </nav>
  );
}

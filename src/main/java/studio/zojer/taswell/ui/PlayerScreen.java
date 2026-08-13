package studio.zojer.taswell.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studio.zojer.taswell.TaswellPaths;
import studio.zojer.taswell.director.MusicDirector;
import studio.zojer.taswell.library.Library;
import studio.zojer.taswell.library.LibraryScanner;
import studio.zojer.taswell.store.ConfigStore;
import studio.zojer.taswell.store.Playlist;
import studio.zojer.taswell.store.PlaylistStore;
import studio.zojer.taswell.store.TaswellConfig;
import studio.zojer.taswell.track.Track;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The player screen opened by the {@code key.taswell.open} keybind (Task 8). Left sidebar:
 * playlists (builtins then customs) + add/rename/delete. Right: a live-filtered search box over
 * a track list. Bottom: transport, shuffle/repeat, volume, refresh. See the task brief for the
 * full layout spec and the task report for what was verified in-game vs. inferred from the
 * mapped sources.
 *
 * <p><b>26.2 render contract</b> (verified against the mapped sources — see the task report):
 * there is no {@code GuiGraphics}/{@code render(GuiGraphics, int, int, float)} pair anymore, not
 * even as a "submit vs. draw" split — {@code Screen} and every {@code AbstractWidget} now
 * override {@code extractRenderState}/{@code extractWidgetRenderState}, drawing through a single
 * {@code GuiGraphicsExtractor}. Plain {@code Button}/{@code EditBox}/{@code OptionInstance}
 * widgets already implement this internally, so {@code addRenderableWidget(...)} usage is
 * unaffected; only the two custom {@code ObjectSelectionList.Entry} subclasses in this package
 * had to target the new shape directly (see their javadoc).
 *
 * <p><b>Custom-playlist browsing, a brief ambiguity resolved:</b> the brief states both (a) "the
 * track list always shows the selected playlist's contents" and (b) a per-row toggle button
 * "when a custom playlist is selected" for adding/removing membership. Taken literally, (a) means
 * a freshly-created (empty) custom playlist would show zero rows, leaving no way to ever add a
 * first track to it — which contradicts the brief's own verification step ("create playlist
 * 'mine', add 2 vanilla + 1 local track"). Resolution used here: when the sidebar selection is a
 * *builtin*, the track list shows that playlist's own resolved contents (unchanged, matches (a)
 * literally — builtins are never edited). When the sidebar selection is a *custom* playlist, the
 * track list instead shows the full catalog ({@link Library#all()}) plus any of that playlist's
 * own track ids that no longer resolve (kept visible, greyed, so they can still be seen and
 * removed) — i.e. "browse everything, toggle membership in the playlist you have selected." This
 * satisfies every bullet in the brief's verification checklist, including the missing-row case
 * (an orphaned id from a deleted local file stays visible after a rescan). Flagged here rather
 * than silently assumed — worth a second look.
 */
public class PlayerScreen extends Screen {
    private static final Logger LOG = LoggerFactory.getLogger(PlayerScreen.class);

    private static final int PANEL_WIDTH_PCT = 85;
    private static final int PANEL_HEIGHT_PCT = 80;
    private static final int SIDEBAR_WIDTH_PCT = 30;
    private static final int GUTTER = 6;
    private static final int TRANSPORT_HEIGHT = 24;
    private static final int SEARCH_HEIGHT = 20;
    private static final int SIDEBAR_BUTTON_ROW_HEIGHT = 20;
    private static final int SIDEBAR_EDIT_ROW_HEIGHT = 18;
    private static final int TRACK_ROW_HEIGHT = 16;
    private static final int PLAYLIST_ROW_HEIGHT = 18;
    /** "sure?" stays armed this many ticks before reverting — per the brief. */
    private static final int DELETE_CONFIRM_TICKS = 40;

    private enum SidebarMode { NORMAL, ADDING, RENAMING }

    private final MusicDirector director = MusicDirector.get();
    private final Library library = director.library();
    private final List<Playlist> customPlaylists = new ArrayList<>();

    private String activePlaylistId;
    private String searchQuery = "";
    private SidebarMode sidebarMode = SidebarMode.NORMAL;
    private String renamingPlaylistId;

    private String armedDeleteId;
    private int armedDeleteExpiresAtTick = -1;
    private int tickCount;

    private PlaylistListWidget playlistList;
    private TrackListWidget trackList;
    private EditBox searchBox;
    private EditBox nameBox;
    private Button renameButton;
    private Button deleteButton;
    private Button shuffleButton;
    private Button repeatButton;
    private Button refreshButton;
    /** Guards against rapid ⟳ clicks spawning overlapping scans whose callbacks could land out of order. */
    private boolean refreshInProgress;

    public PlayerScreen() {
        super(Component.translatable("screen.taswell.title"));
    }

    @Override
    protected void init() {
        this.customPlaylists.clear();
        this.customPlaylists.addAll(PlaylistStore.load(TaswellPaths.playlistsFile()));
        if (this.activePlaylistId == null) {
            this.activePlaylistId = director.activePlaylistId();
        }

        int panelWidth = this.width * PANEL_WIDTH_PCT / 100;
        int panelHeight = this.height * PANEL_HEIGHT_PCT / 100;
        int panelX = (this.width - panelWidth) / 2;
        int panelY = (this.height - panelHeight) / 2;

        int sidebarWidth = panelWidth * SIDEBAR_WIDTH_PCT / 100;
        int rightX = panelX + sidebarWidth + GUTTER;
        int rightWidth = panelWidth - sidebarWidth - GUTTER;
        int transportY = panelY + panelHeight - TRANSPORT_HEIGHT;

        buildSidebar(panelX, panelY, sidebarWidth, panelHeight);
        buildRightSide(rightX, panelY, rightWidth, transportY - panelY - GUTTER);
        buildTransportBar(panelX, transportY, panelWidth, TRANSPORT_HEIGHT);
    }

    @Override
    public void tick() {
        tickCount++;
        if (armedDeleteId != null && tickCount >= armedDeleteExpiresAtTick) {
            armedDeleteId = null;
            if (deleteButton != null) {
                deleteButton.setMessage(deleteLabel());
            }
        }
    }

    @Override
    public void onClose() {
        // The volume slider (an OptionInstance-backed widget, see buildTransportBar) already
        // applies live on every drag frame via its own built-in listener — this is the single
        // point where the resulting value gets written to disk, so a whole-session drag only
        // costs one synchronous file write, not one per frame.
        this.minecraft.options.save();
        super.onClose();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (nameBox != null && nameBox.isFocused() && event.isConfirmation()) {
            commitNameEdit();
            return true;
        }
        if (super.keyPressed(event)) {
            return true;
        }
        boolean typing = (searchBox != null && searchBox.isFocused()) || (nameBox != null && nameBox.isFocused());
        if (typing) {
            return false;
        }
        if (event.isConfirmation()) {
            Track selected = trackList != null ? trackList.selectedTrack() : null;
            if (selected != null) {
                onPlayTrack(selected);
                return true;
            }
        } else if (event.key() == GLFW.GLFW_KEY_SLASH && searchBox != null) {
            setInitialFocus(searchBox);
            return true;
        }
        return false;
    }

    // ---- sidebar --------------------------------------------------------------------------

    private void buildSidebar(int x, int y, int width, int height) {
        int editRowHeight = sidebarMode == SidebarMode.NORMAL ? 0 : SIDEBAR_EDIT_ROW_HEIGHT + 2;
        int listHeight = height - SIDEBAR_BUTTON_ROW_HEIGHT - editRowHeight - 4;

        playlistList = new PlaylistListWidget(this.minecraft, width, listHeight, y, PLAYLIST_ROW_HEIGHT, this::onPlaylistSelected);
        playlistList.setPosition(x, y);
        playlistList.setPlaylists(library.builtins(), customPlaylists, activePlaylistId);
        addRenderableWidget(playlistList);

        int rowY = y + listHeight + 2;
        if (sidebarMode != SidebarMode.NORMAL) {
            nameBox = new EditBox(this.font, x, rowY, width, SIDEBAR_EDIT_ROW_HEIGHT, Component.translatable("gui.taswell.playlist_name"));
            nameBox.setMaxLength(64);
            nameBox.setHint(Component.translatable("gui.taswell.playlist_name"));
            if (sidebarMode == SidebarMode.RENAMING) {
                findPlaylistById(renamingPlaylistId).ifPresent(p -> nameBox.setValue(p.name()));
            }
            addRenderableWidget(nameBox);
            setInitialFocus(nameBox);
            rowY += SIDEBAR_EDIT_ROW_HEIGHT + 2;
        } else {
            nameBox = null;
        }

        boolean editable = !isBuiltin(activePlaylistId);
        int btnWidth = (width - 4) / 3;
        int bx = x;
        addRenderableWidget(Button.builder(Component.literal("+"), b -> onAddClicked())
                .bounds(bx, rowY, btnWidth, SIDEBAR_BUTTON_ROW_HEIGHT).build());
        bx += btnWidth + 2;
        renameButton = Button.builder(Component.literal("✎"), b -> onRenameClicked())
                .bounds(bx, rowY, btnWidth, SIDEBAR_BUTTON_ROW_HEIGHT).build();
        renameButton.active = editable;
        addRenderableWidget(renameButton);
        bx += btnWidth + 2;
        deleteButton = Button.builder(deleteLabel(), b -> onDeleteClicked())
                .bounds(bx, rowY, width - (bx - x), SIDEBAR_BUTTON_ROW_HEIGHT).build();
        deleteButton.active = editable;
        addRenderableWidget(deleteButton);
    }

    private void onPlaylistSelected(Playlist playlist) {
        this.activePlaylistId = playlist.id();
        director.setActivePlaylist(playlist.id());
        // A "sure?" armed on a *different* playlist's delete button must not silently carry over
        // and fire on this one if the user comes back to it later — clear it on every selection
        // change, not just when the button itself is clicked or its own timeout expires.
        armedDeleteId = null;
        boolean editable = !isBuiltin(activePlaylistId);
        if (renameButton != null) {
            renameButton.active = editable;
        }
        if (deleteButton != null) {
            deleteButton.active = editable;
            deleteButton.setMessage(deleteLabel());
        }
        refreshTrackList();
    }

    private void onAddClicked() {
        sidebarMode = sidebarMode == SidebarMode.ADDING ? SidebarMode.NORMAL : SidebarMode.ADDING;
        renamingPlaylistId = null;
        this.rebuildWidgets();
    }

    private void onRenameClicked() {
        if (isBuiltin(activePlaylistId)) {
            return;
        }
        if (sidebarMode == SidebarMode.RENAMING) {
            sidebarMode = SidebarMode.NORMAL;
            renamingPlaylistId = null;
        } else {
            sidebarMode = SidebarMode.RENAMING;
            renamingPlaylistId = activePlaylistId;
        }
        this.rebuildWidgets();
    }

    private void onDeleteClicked() {
        if (isBuiltin(activePlaylistId)) {
            return;
        }
        if (armedDeleteId != null && armedDeleteId.equals(activePlaylistId)) {
            armedDeleteId = null;
            deleteActivePlaylist();
        } else {
            armedDeleteId = activePlaylistId;
            armedDeleteExpiresAtTick = tickCount + DELETE_CONFIRM_TICKS;
            deleteButton.setMessage(deleteLabel());
        }
    }

    private void deleteActivePlaylist() {
        String idToDelete = activePlaylistId;
        customPlaylists.removeIf(p -> p.id().equals(idToDelete));
        savePlaylists();
        // library.builtins() always synthesizes exactly three entries (c418/local/all — see
        // Library#builtins), so this is currently unreachable, but a defensive guard here is
        // cheap and avoids an IndexOutOfBoundsException crashing the screen if that invariant
        // ever changes. Falling back to the id just deleted degrades no worse than before this
        // guard existed — MusicDirector.activePlaylist() already handles an unresolved id.
        List<Playlist> builtins = library.builtins();
        String fallback = builtins.isEmpty() ? idToDelete : builtins.get(0).id();
        activePlaylistId = fallback;
        director.setActivePlaylist(fallback);
        this.rebuildWidgets();
    }

    private void commitNameEdit() {
        String name = nameBox.getValue().trim();
        if (!name.isEmpty()) {
            if (sidebarMode == SidebarMode.ADDING) {
                customPlaylists.add(new Playlist("custom:" + UUID.randomUUID(), name, List.of()));
            } else if (sidebarMode == SidebarMode.RENAMING) {
                String targetId = renamingPlaylistId;
                customPlaylists.replaceAll(p -> p.id().equals(targetId) ? new Playlist(p.id(), name, p.trackIds()) : p);
            }
            savePlaylists();
        }
        sidebarMode = SidebarMode.NORMAL;
        renamingPlaylistId = null;
        this.rebuildWidgets();
    }

    private Component deleteLabel() {
        if (armedDeleteId != null && armedDeleteId.equals(activePlaylistId)) {
            return Component.literal("sure?").withStyle(ChatFormatting.RED);
        }
        return Component.literal("🗑");
    }

    // ---- right side (search + track list) --------------------------------------------------

    private void buildRightSide(int x, int y, int width, int height) {
        searchBox = new EditBox(this.font, x, y, width, SEARCH_HEIGHT, Component.translatable("gui.taswell.search"));
        searchBox.setMaxLength(64);
        searchBox.setHint(Component.translatable("gui.taswell.search"));
        searchBox.setValue(searchQuery);
        searchBox.setResponder(value -> {
            this.searchQuery = value;
            refreshTrackList();
        });
        addRenderableWidget(searchBox);

        int listY = y + SEARCH_HEIGHT + GUTTER;
        int listHeight = height - SEARCH_HEIGHT - GUTTER;
        trackList = new TrackListWidget(this.minecraft, width, listHeight, listY, TRACK_ROW_HEIGHT, this::onPlayTrack, this::onToggleMembership);
        trackList.setPosition(x, listY);
        addRenderableWidget(trackList);
        refreshTrackList();
    }

    private void onPlayTrack(Track track) {
        director.playNow(track.id());
        refreshTrackList();
    }

    private void onToggleMembership(String trackId) {
        if (isBuiltin(activePlaylistId)) {
            return;
        }
        for (int i = 0; i < customPlaylists.size(); i++) {
            Playlist current = customPlaylists.get(i);
            if (!current.id().equals(activePlaylistId)) {
                continue;
            }
            List<String> ids = new ArrayList<>(current.trackIds());
            if (!ids.remove(trackId)) {
                ids.add(trackId);
            }
            customPlaylists.set(i, new Playlist(current.id(), current.name(), List.copyOf(ids)));
            break;
        }
        savePlaylists();
        refreshTrackList();
    }

    /**
     * Rebuilds the track list's rows from the current sidebar selection + search text. See this
     * class's javadoc for why a selected custom playlist shows the full catalog (plus its own
     * orphaned ids) rather than only its own resolved contents.
     */
    private void refreshTrackList() {
        if (trackList == null) {
            return;
        }
        Playlist selected = findPlaylistById(activePlaylistId)
                .orElseGet(() -> new Playlist(activePlaylistId, String.valueOf(activePlaylistId), List.of()));
        boolean editable = !selected.builtin();

        List<Library.Entry> rows;
        Set<String> memberIds;
        if (editable) {
            memberIds = new HashSet<>(selected.trackIds());
            rows = new ArrayList<>();
            for (Track t : library.all()) {
                rows.add(new Library.Entry(t.id(), t));
            }
            for (String id : selected.trackIds()) {
                if (library.byId(id).isEmpty()) {
                    rows.add(new Library.Entry(id, null));
                }
            }
        } else {
            memberIds = Set.of();
            rows = library.resolve(selected);
        }

        String needle = searchQuery.trim().toLowerCase(Locale.ROOT);
        List<Library.Entry> filtered = needle.isEmpty() ? rows : rows.stream().filter(e -> matchesQuery(e, needle)).toList();
        String nowPlayingId = director.nowPlaying().map(Track::id).orElse(null);
        trackList.setEntries(filtered, nowPlayingId, editable, memberIds);
    }

    private static boolean matchesQuery(Library.Entry entry, String needleLower) {
        Track track = entry.track();
        if (track == null) {
            return entry.trackId().toLowerCase(Locale.ROOT).contains(needleLower);
        }
        return contains(track.title(), needleLower) || contains(track.artist(), needleLower);
    }

    private static boolean contains(String haystack, String needleLower) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needleLower);
    }

    // ---- transport bar ----------------------------------------------------------------------

    private void buildTransportBar(int x, int y, int width, int height) {
        int bw = 22;
        int bx = x;
        addRenderableWidget(Button.builder(Component.literal("⏮"), b -> director.previous())
                .bounds(bx, y, bw, height).build());
        bx += bw + 2;
        addRenderableWidget(Button.builder(Component.literal("⏯"), b -> director.togglePause())
                .bounds(bx, y, bw, height).build());
        bx += bw + 2;
        addRenderableWidget(Button.builder(Component.literal("⏭"), b -> director.next())
                .bounds(bx, y, bw, height).build());
        bx += bw + 8;

        shuffleButton = Button.builder(shuffleLabel(), b -> onShuffleClicked()).bounds(bx, y, bw + 8, height).build();
        addRenderableWidget(shuffleButton);
        bx += bw + 10;

        repeatButton = Button.builder(repeatLabel(), b -> onRepeatClicked()).bounds(bx, y, 46, height).build();
        addRenderableWidget(repeatButton);

        int refreshX = x + width - bw;
        refreshButton = Button.builder(Component.literal("⟳"), b -> onRefresh())
                .bounds(refreshX, y, bw, height).build();
        refreshButton.active = !refreshInProgress;
        addRenderableWidget(refreshButton);

        int volumeWidth = 100;
        int volumeX = refreshX - GUTTER - volumeWidth;
        Options options = this.minecraft.options;
        // No save() here on purpose — this callback fires on every drag-move frame (verified
        // against the mapped OptionInstance/AbstractOptionSliderButton sources:
        // applyValueImmediately() defaults true for sound sliders, so applyValue() → this
        // callback runs per mouse-move during a drag), and Options.save() is a synchronous
        // FileOutputStream write. The slider still applies live — OptionInstance.set(...)
        // updates the live value and its own built-in listener already calls
        // soundManager.refreshCategoryVolume(MUSIC) regardless of this callback. Persistence is
        // handled once, on close (see onClose()) — matches vanilla's own options-screen
        // convention of writing to disk on close rather than per frame.
        addRenderableWidget(options.getSoundSourceOptionInstance(SoundSource.MUSIC)
                .createButton(options, volumeX, y, volumeWidth));
    }

    private void onShuffleClicked() {
        director.setShuffle(!director.isShuffle());
        shuffleButton.setMessage(shuffleLabel());
    }

    private void onRepeatClicked() {
        director.cycleRepeat();
        repeatButton.setMessage(repeatLabel());
    }

    private Component shuffleLabel() {
        boolean on = director.isShuffle();
        return Component.literal("⇄").withStyle(on ? ChatFormatting.YELLOW : ChatFormatting.GRAY);
    }

    private Component repeatLabel() {
        return switch (director.repeatMode()) {
            case OFF -> Component.literal("↻ off");
            case ONE -> Component.literal("↻ 1");
            case PLAYLIST -> Component.literal("↻ all");
        };
    }

    /**
     * {@link #refreshInProgress} guards this against rapid double/triple clicks: without it, a
     * second click while a scan is still in flight would spawn a concurrent scan, and whichever
     * of the two background threads happens to finish (and post back to the client thread) last
     * wins — not necessarily the more recent click — silently reverting to a stale result. The
     * button is also disabled for the duration so there's nothing to click during the guard
     * window either, not just a click that's ignored.
     *
     * <p>Both the background scan and the client-thread post-back are defended against throwing
     * an exception that would leave {@link #refreshInProgress} stuck {@code true} forever (and
     * the button permanently disabled) — {@link LibraryScanner#scan} doesn't throw for ordinary
     * cases (a missing folder yields an empty list), but this guards the unusual ones (e.g. an
     * IO error mid-scan) anyway, since a wedged refresh button for the rest of the screen's life
     * is a worse failure mode than a scan that silently fails once and can be retried.
     */
    private void onRefresh() {
        if (refreshInProgress) {
            return;
        }
        refreshInProgress = true;
        if (refreshButton != null) {
            refreshButton.active = false;
        }
        TaswellConfig cfg = ConfigStore.load(TaswellPaths.configFile());
        Path folder = cfg.musicFolder != null ? Path.of(cfg.musicFolder) : TaswellPaths.defaultMusicDir();
        Util.backgroundExecutor().execute(() -> {
            List<Track> scanned;
            try {
                scanned = LibraryScanner.scan(folder);
            } catch (RuntimeException e) {
                LOG.warn("taswell: refresh scan of {} failed — leaving the local library unchanged", folder, e);
                scanned = null;
            }
            List<Track> result = scanned;
            Minecraft.getInstance().execute(() -> {
                try {
                    if (result != null) {
                        library.setLocal(result);
                    }
                    refreshTrackList();
                } finally {
                    refreshInProgress = false;
                    if (refreshButton != null) {
                        refreshButton.active = true;
                    }
                }
            });
        });
    }

    // ---- shared helpers ---------------------------------------------------------------------

    private Optional<Playlist> findPlaylistById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return library.builtins().stream().filter(p -> p.id().equals(id)).findFirst()
                .or(() -> customPlaylists.stream().filter(p -> p.id().equals(id)).findFirst());
    }

    private void savePlaylists() {
        PlaylistStore.save(TaswellPaths.playlistsFile(), customPlaylists);
    }

    private static boolean isBuiltin(String id) {
        return id != null && id.startsWith("builtin:");
    }
}

package studio.zojer.taswell.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import studio.zojer.taswell.library.Library;
import studio.zojer.taswell.track.Track;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The main track list. Rows come from {@link Library.Entry}: {@code track() == null} means a
 * missing/orphaned id (see {@code PlayerScreen#refreshTrackList} for how that list is built) —
 * rendered greyed + italic, per the brief, with no play action.
 *
 * <p>When {@code membershipEditable} is set (a custom playlist is the sidebar's current
 * selection), non-missing rows get a small trailing {@code +}/{@code -} toggle reflecting {@code
 * memberIds} — click it to add/remove that track from the selected custom playlist. Double-click
 * or Enter (Enter is handled at the screen level, see {@link #selectedTrack()}) plays a track.
 *
 * <p>Render contract: same 26.2 {@code extractContent(GuiGraphicsExtractor, ...)} shape as {@link
 * PlaylistListWidget} — see that class's javadoc for what was verified against the mapped
 * sources.
 */
public final class TrackListWidget extends ObjectSelectionList<TrackListWidget.Entry> {
    private static final int TOGGLE_WIDTH = 10;

    private final Consumer<Track> onPlay;
    private final Consumer<String> onToggleMembership;

    private String nowPlayingId;
    private boolean membershipEditable;
    private Set<String> memberIds = Set.of();

    public TrackListWidget(Minecraft minecraft, int width, int height, int y, int itemHeight,
                            Consumer<Track> onPlay, Consumer<String> onToggleMembership) {
        super(minecraft, width, height, y, itemHeight);
        this.onPlay = onPlay;
        this.onToggleMembership = onToggleMembership;
    }

    @Override
    public int getRowWidth() {
        return Math.max(1, this.width - 10);
    }

    /**
     * Replaces the row set wholesale. {@code nowPlayingId} (nullable) gets the {@code ♪} prefix;
     * {@code membershipEditable}/{@code memberIds} drive the per-row toggle button (see class
     * javadoc). Selection is not preserved across a refresh — see the task report's known
     * limitations.
     */
    public void setEntries(List<Library.Entry> entries, String nowPlayingId, boolean membershipEditable, Set<String> memberIds) {
        this.nowPlayingId = nowPlayingId;
        this.membershipEditable = membershipEditable;
        this.memberIds = memberIds;
        this.replaceEntries(entries.stream().map(Entry::new).toList());
    }

    /** The currently-selected row's track, or {@code null} if nothing is selected or the selected row is missing. */
    public Track selectedTrack() {
        Entry selected = getSelected();
        return selected == null ? null : selected.entry.track();
    }

    public final class Entry extends ObjectSelectionList.Entry<Entry> {
        private final Library.Entry entry;

        private Entry(Library.Entry entry) {
            this.entry = entry;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            var font = TrackListWidget.this.minecraft.font;
            Track track = entry.track();
            boolean missing = track == null;
            boolean nowPlaying = !missing && track.id().equals(nowPlayingId);
            String base = missing ? entry.trackId() : track.title() + " — " + track.artist();
            String label = nowPlaying ? "♪ " + base : base;
            // Full ARGB required: 26.2's GuiGraphicsExtractor.text() silently drops any draw
            // whose color has a zero alpha byte (if (ARGB.alpha(color) != 0) { ... }).
            int color = missing ? 0xFF808080 : 0xFFFFFFFF;
            Component text = missing ? Component.literal(label).withStyle(ChatFormatting.ITALIC) : Component.literal(label);
            int textY = getContentY() + (getContentHeight() - font.lineHeight) / 2;
            graphics.text(font, text, getContentX(), textY, color);

            if (membershipEditable && !missing) {
                boolean member = memberIds.contains(track.id());
                String glyph = member ? "-" : "+";
                int glyphColor = member ? 0xFFFF5555 : 0xFF55FF55;
                graphics.text(font, glyph, getContentRight() - TOGGLE_WIDTH, textY, glyphColor);
            }
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            Track track = entry.track();
            if (membershipEditable && track != null && event.x() >= getContentRight() - TOGGLE_WIDTH) {
                onToggleMembership.accept(track.id());
                return true;
            }
            if (doubleClick && track != null) {
                onPlay.accept(track);
            }
            return true;
        }

        @Override
        public Component getNarration() {
            Track track = entry.track();
            return track != null ? Component.literal(track.title() + " " + track.artist()) : Component.literal(entry.trackId());
        }
    }
}

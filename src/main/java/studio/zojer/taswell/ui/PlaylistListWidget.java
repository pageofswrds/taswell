package studio.zojer.taswell.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import studio.zojer.taswell.store.Playlist;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The sidebar's playlist list — builtins first, then customs, in that fixed order (see {@link
 * #setPlaylists}). A thin {@link ObjectSelectionList} subclass: rows just hold a {@link
 * Playlist} and draw its name; a click both highlights the row (the base class's own
 * click-to-focus-to-select machinery, see {@code ObjectSelectionList.Entry#mouseClicked}) and
 * fires {@code onSelect} immediately — per the brief, sidebar selection sets the active rotation
 * playlist on a single click, not a separate "confirm" step.
 *
 * <p>26.2's render contract (verified against the mapped sources, see the task report): there is
 * no {@code GuiGraphics}/{@code render(...)} pair anymore. Entries override {@link
 * ObjectSelectionList.Entry#extractContent}, drawing through {@link GuiGraphicsExtractor}, whose
 * text method is {@code text(Font, ..., color)} (not {@code drawString}).
 */
public final class PlaylistListWidget extends ObjectSelectionList<PlaylistListWidget.Entry> {
    private final Consumer<Playlist> onSelect;

    public PlaylistListWidget(Minecraft minecraft, int width, int height, int y, int itemHeight, Consumer<Playlist> onSelect) {
        super(minecraft, width, height, y, itemHeight);
        this.onSelect = onSelect;
    }

    @Override
    public int getRowWidth() {
        return Math.max(1, this.width - 10);
    }

    /**
     * Replaces the row set wholesale — builtins in {@code builtins}' order, then {@code
     * customs} — and restores the selection highlight for {@code selectedId} if it matches one
     * of the new rows (it always should; this is just re-establishing the highlight after a
     * rebuild, not driving any playback decision).
     */
    public void setPlaylists(List<Playlist> builtins, List<Playlist> customs, String selectedId) {
        List<Entry> entries = new ArrayList<>(builtins.size() + customs.size());
        for (Playlist p : builtins) {
            entries.add(new Entry(p));
        }
        for (Playlist p : customs) {
            entries.add(new Entry(p));
        }
        this.replaceEntries(entries);
        for (Entry entry : entries) {
            if (entry.playlist.id().equals(selectedId)) {
                this.setSelected(entry);
                break;
            }
        }
    }

    public final class Entry extends ObjectSelectionList.Entry<Entry> {
        private final Playlist playlist;

        private Entry(Playlist playlist) {
            this.playlist = playlist;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            int color = playlist.builtin() ? 0xC0C0C0 : 0xFFFFFF;
            int textY = getContentY() + (getContentHeight() - PlaylistListWidget.this.minecraft.font.lineHeight) / 2;
            graphics.text(PlaylistListWidget.this.minecraft.font, playlist.name(), getContentX(), textY, color);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            onSelect.accept(playlist);
            return true;
        }

        @Override
        public Component getNarration() {
            return Component.literal(playlist.name());
        }
    }
}

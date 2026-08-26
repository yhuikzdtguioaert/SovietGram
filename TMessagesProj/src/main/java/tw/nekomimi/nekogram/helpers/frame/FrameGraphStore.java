package tw.nekomimi.nekogram.helpers.frame;

import androidx.annotation.Nullable;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.helpers.CustomProfileFrame;
import tw.nekomimi.nekogram.helpers.CustomProfileHelper;

/**
 * Where the frame being authored lives between sessions.
 *
 * <p>Two keys are written together and always together: the spec, which is what the profile draws and
 * what travels to other users, and the graph, which is the authoring state and stays on this device.
 * Saving compiles one into the other, so the two can never disagree about what the user just drew.
 *
 * <p><b>They can still disagree from the other side</b>, and this is where the reference and this app
 * part company. There, a mismatch meant the graph won and the spec was rewritten from it. Here the
 * spec wins: the two things that overwrite it are installing a frame from the workshop and importing
 * a look, and in both cases the user is looking at the new frame on their profile — silently
 * replacing it with the last thing they drew in the studio would undo a change they can see. So the
 * graph is rebuilt from the spec instead, which is lossy but honest.
 */
public final class FrameGraphStore {

    private FrameGraphStore() {
    }

    /** The graph to edit: the stored one, or one laid out from the spec if there is none. */
    public static FrameGraph graph() {
        final String stored = NekoConfig.customProfileFrameGraph.String();
        if (stored != null && stored.length() > 0) {
            final FrameGraph graph = FrameGraph.parse(stored);
            if (graph.count() > 0 && !stale(graph)) {
                return graph;
            }
        }
        return FrameGraphBuild.of(spec());
    }

    /** The frame currently installed, whoever wrote it. */
    public static FrameSpec spec() {
        return FrameSpec.parse(NekoConfig.customProfileFrameSpec.String());
    }

    /**
     * Whether the stored graph no longer describes the frame that is actually installed.
     *
     * <p>Compared as the written text rather than by signature, and that is not a shortcut: a spec
     * only writes the keys its mode uses, so two layers that draw identically can differ in a field
     * neither of them reads — a turn on a stamp, say, which the renderer ignores. By signature those
     * two look different and the user's graph would be thrown away every time they opened the studio.
     * {@link #save} writes exactly this text, so this is an equality that holds when it should.
     */
    public static boolean stale(@Nullable FrameGraph graph) {
        if (graph == null) {
            return true;
        }
        final String stored = NekoConfig.customProfileFrameSpec.String();
        return !FrameGraphBuild.spec(graph).encode().equals(stored == null ? "" : stored);
    }

    /** Writes both keys and repaints. */
    public static void save(@Nullable FrameGraph graph) {
        if (graph == null) {
            return;
        }
        final FrameSpec spec = FrameGraphBuild.spec(graph);
        NekoConfig.customProfileFrameGraph.setConfigString(graph.encode());
        NekoConfig.customProfileFrameSpec.setConfigString(spec.encode());
        changed();
    }

    /** Installs a frame written elsewhere, and lays out a graph for it. */
    public static void apply(@Nullable FrameSpec spec) {
        final FrameSpec wanted = spec == null ? FrameSpec.EMPTY : spec;
        NekoConfig.customProfileFrameSpec.setConfigString(wanted.encode());
        NekoConfig.customProfileFrameGraph.setConfigString(
                FrameGraphBuild.of(wanted).encode());
        changed();
    }

    /** Takes the frame off. */
    public static void clear() {
        NekoConfig.customProfileFrameSpec.setConfigString("");
        NekoConfig.customProfileFrameGraph.setConfigString("");
        changed();
    }

    /**
     * Drops the stored graph without touching the spec. Called wherever the spec is overwritten from
     * outside the studio, so the next open lays a fresh graph out for whatever was installed.
     */
    public static void forgetGraph() {
        NekoConfig.customProfileFrameGraph.setConfigString("");
    }

    private static void changed() {
        CustomProfileFrame.invalidate();
        // The one hook every look edit goes through: it repaints the open profiles and schedules the
        // push that carries the new spec to everybody who can see them.
        CustomProfileHelper.onSettingsChanged();
    }
}

package tw.nekomimi.nekogram.helpers.frame;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Version 2 .frame archive compatible with the reference plugin's frame.json layout. */
public final class FramePackage {
    private static final int MAX_BYTES = 64 * 1024 * 1024;

    public static final class Document {
        public final String name;
        public final String graph;
        public final String spec;

        private Document(String name, String graph, String spec) {
            this.name = name;
            this.graph = graph;
            this.spec = spec;
        }
    }

    private FramePackage() { }

    public static void write(Context context, String name, String graphText, String specText,
                             OutputStream destination) throws Exception {
        final FrameGraph graph = FrameGraph.parse(graphText);
        final FrameSpec spec = FrameSpec.parse(specText);
        if (spec.isEmpty()) throw new IllegalArgumentException("Frame is empty");
        final Set<String> sources = new HashSet<>(spec.assets());
        sources.addAll(graph.sources());
        final Map<String, String> swapped = new HashMap<>();
        final Map<String, byte[]> textures = new HashMap<>();
        int total = 0;
        for (String source : sources) {
            if (source == null || source.isEmpty() || FrameBlanks.is(source)) continue;
            final byte[] data = readSource(context, source);
            total += data.length;
            if (total > MAX_BYTES) throw new IllegalArgumentException("Frame exceeds 64 MiB");
            final String path = "textures/" + hash(data) + extension(source);
            swapped.put(source, path);
            textures.put(path, data);
        }
        final JSONObject manifest = new JSONObject().put("version", 2).put("name", name)
                .put("spec", FrameSpec.swap(spec, swapped).encode())
                .put("graph", graph.swap(swapped).encode());
        try (ZipOutputStream zip = new ZipOutputStream(destination)) {
            zip.setLevel(9);
            zip.putNextEntry(new ZipEntry("frame.json"));
            zip.write(manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            for (Map.Entry<String, byte[]> texture : textures.entrySet()) {
                zip.putNextEntry(new ZipEntry(texture.getKey()));
                zip.write(texture.getValue());
                zip.closeEntry();
            }
        }
    }

    public static Document read(Context context, InputStream source) throws Exception {
        JSONObject manifest = null;
        final Map<String, byte[]> textures = new HashMap<>();
        int total = 0;
        try (ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                final String name = entry.getName();
                if (!"frame.json".equals(name) && !name.matches("textures/[a-f0-9]{16}\\.[a-z0-9]{1,5}")) {
                    throw new IllegalArgumentException("Invalid frame entry");
                }
                final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                final byte[] buffer = new byte[16 * 1024];
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_BYTES) throw new IllegalArgumentException("Frame exceeds 64 MiB");
                    bytes.write(buffer, 0, count);
                }
                if ("frame.json".equals(name)) {
                    if (bytes.size() > 1024 * 1024) throw new IllegalArgumentException("Frame manifest too large");
                    manifest = new JSONObject(bytes.toString("UTF-8"));
                } else {
                    textures.put(name, bytes.toByteArray());
                }
                zip.closeEntry();
            }
        }
        if (manifest == null || manifest.optInt("version") != 2) {
            throw new IllegalArgumentException("Unsupported frame file");
        }
        final FrameSpec spec = FrameSpec.parse(manifest.optString("spec"));
        if (spec.isEmpty()) throw new IllegalArgumentException("Frame is empty");
        final File directory = new File(context.getFilesDir(), "frame-assets");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Cannot create frame asset directory");
        }
        final Map<String, String> swapped = new HashMap<>();
        for (Map.Entry<String, byte[]> texture : textures.entrySet()) {
            final String path = texture.getKey();
            final byte[] data = texture.getValue();
            if (!path.startsWith("textures/" + hash(data) + ".")) {
                throw new IllegalArgumentException("Frame texture checksum mismatch");
            }
            final File target = new File(directory, path.substring("textures/".length()));
            if (!target.isFile()) {
                try (FileOutputStream out = new FileOutputStream(target)) {
                    out.write(data);
                }
            }
            swapped.put(path, target.getAbsolutePath());
        }
        for (String asset : spec.assets()) {
            if (asset != null && asset.startsWith("textures/") && !swapped.containsKey(asset)) {
                throw new IllegalArgumentException("Missing frame texture");
            }
        }
        final FrameGraph graph = FrameGraph.parse(manifest.optString("graph"));
        final FrameSpec local = FrameSpec.swap(spec, swapped);
        return new Document(manifest.optString("name", "Frame"),
                graph.swap(swapped).encode(), local.encode());
    }

    private static byte[] readSource(Context context, String source) throws Exception {
        final InputStream input;
        if (source.startsWith("content://")) {
            input = context.getContentResolver().openInputStream(Uri.parse(source));
        } else if (source.startsWith("https://") || source.startsWith("http://")) {
            final URLConnection connection = new URL(source).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            input = connection.getInputStream();
        } else {
            input = new FileInputStream(source);
        }
        if (input == null) throw new IllegalArgumentException("Missing frame texture");
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            final byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > MAX_BYTES) throw new IllegalArgumentException("Frame texture too large");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    private static String hash(byte[] data) throws Exception {
        final byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        final StringBuilder result = new StringBuilder(16);
        for (byte b : digest) result.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        return result.substring(0, 16);
    }

    private static String extension(String source) {
        final String clean = source.split("[?#]", 2)[0].toLowerCase(java.util.Locale.ROOT);
        final int dot = clean.lastIndexOf('.');
        final String ext = dot < 0 ? "png" : clean.substring(dot + 1);
        return ext.matches("[a-z0-9]{1,5}") ? "." + ext : ".png";
    }
}

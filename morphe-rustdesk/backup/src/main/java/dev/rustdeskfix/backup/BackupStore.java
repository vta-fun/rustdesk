package dev.rustdeskfix.backup;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Base64;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

public final class BackupStore {
    private static final String TAG = "RustDeskBackup";
    private static final String PENDING = "rustdesk-connection-restore.json";
    private static final String NETWORK = "RustDesk2.toml";
    private static final String LOCAL = "RustDesk_local.toml";
    private static final String[] SERVER_KEYS = {
        "custom-rendezvous-server", "relay-server", "api-server", "key"
    };
    private static final int MAX_BACKUP_BYTES = 2 * 1024 * 1024;
    private static final int MAX_CONFIG_BYTES = 1024 * 1024;
    private static final int MAX_RESTORE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_PEERS = 1000;
    private static final int MAX_FIELD_LENGTH = 2048;

    private BackupStore() {}

    public static synchronized byte[] exportBackup(Context context) throws IOException {
        File root = configRoot(context);
        TomlSections network = readToml(child(root, NETWORK));
        TomlSections local = readToml(child(root, LOCAL));
        try {
            JSONObject server = new JSONObject();
            for (String key : SERVER_KEYS) server.put(key, network.string("options", key));

            JSONArray peers = new JSONArray();
            File directory = child(root, "peers");
            if (directory.exists()) {
                File[] files = directory.listFiles();
                if (files == null) throw new IOException("Unable to read the peer directory");
                for (File file : files) {
                    String name = file.getName();
                    if (!file.isFile() || !name.endsWith(".toml")) continue;
                    String id = decodePeerFile(name);
                    if (id == null) continue;
                    TomlSections config = readToml(child(directory, name));
                    String platform = config.string("info", "platform");
                    if (platform.isEmpty()) continue; // RustDesk omits these from recent peers.
                    peers.put(new JSONObject()
                        .put("id", id)
                        .put("alias", config.string("options", "alias"))
                        .put("username", config.string("info", "username"))
                        .put("hostname", config.string("info", "hostname"))
                        .put("platform", platform)
                        .put("lastUsed", file.lastModified()));
                    if (peers.length() > MAX_PEERS) throw new IOException("Too many peers to back up");
                }
            }

            JSONObject backup = new JSONObject()
                .put("format", "rustdesk-connections")
                .put("version", 1)
                .put("server", server)
                .put("lastPeerId", local.string("", "remote_id"))
                .put("favorites", new JSONArray(local.strings("", "fav")))
                .put("peers", peers);
            byte[] bytes = backup.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_BACKUP_BYTES) throw new IOException("Backup is too large");
            validate(backup);
            return bytes;
        } catch (JSONException failure) {
            throw new IOException("Invalid connection settings", failure);
        }
    }

    public static RestoreSummary inspectBackup(InputStream input) throws IOException {
        byte[] bytes = readLimited(input, MAX_BACKUP_BYTES);
        try {
            String json = decode(bytes);
            validateJsonStructure(json);
            JSONTokener reader = new JSONTokener(json);
            Object value = reader.nextValue();
            if (!(value instanceof JSONObject) || reader.nextClean() != 0) {
                throw new IOException("Invalid connection backup");
            }
            return validate((JSONObject) value);
        } catch (JSONException failure) {
            throw new IOException("Invalid connection backup", failure);
        }
    }

    public static synchronized void stageRestore(Context context, RestoreSummary summary) throws IOException {
        planRestore(configRoot(context), summary);
        byte[] bytes = summary.backup.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BACKUP_BYTES) throw new IOException("Backup is too large");
        writeAtomically(pending(context), bytes);
    }

    public static synchronized boolean hasPendingRestore(Context context) {
        return pending(context).isFile();
    }

    // Invoked before the Rust core caches its settings during Application startup.
    public static synchronized void applyPending(Context context) {
        try {
            File pending = pending(context);
            if (!pending.isFile()) return;
            RestoreSummary summary;
            try (InputStream input = new FileInputStream(pending)) {
                summary = inspectBackup(input);
            }

            List<Change> plan = planRestore(configRoot(context), summary);
            List<Change> committed = new ArrayList<>();
            try {
                for (Change change : plan) {
                    if (!Arrays.equals(change.before, change.after)) {
                        writeAtomically(change.file, change.after);
                        committed.add(change);
                    }
                }
            } catch (IOException | RuntimeException failure) {
                // A failed write leaves its target intact. Restore earlier files in reverse order.
                Collections.reverse(committed);
                for (Change change : committed) change.rollback();
                throw failure;
            }
            for (Change change : plan) {
                if (change.modified > 0 && change.modified != change.previousModified) {
                    change.file.setLastModified(change.modified);
                }
            }
            // An interrupted restore remains pending. Reapplying the same merge is idempotent.
            if (!pending.delete()) Log.w(TAG, "Could not clear the pending connection restore");
        } catch (IOException | RuntimeException failure) {
            Log.w(TAG, "Could not apply the pending connection restore");
        }
    }

    private static List<Change> planRestore(File root, RestoreSummary summary) throws IOException {
        List<Change> plan = new ArrayList<>();
        File networkFile = child(root, NETWORK);
        File localFile = child(root, LOCAL);
        TomlSections localConfig = readToml(localFile);
        requireSignOutForServerChange(readToml(networkFile), localConfig, summary.server);

        Map<String, String> network = new LinkedHashMap<>();
        for (Map.Entry<String, String> field : summary.server.entrySet()) {
            network.put(field.getKey(), field.getValue().isEmpty() ? null : TomlSections.quote(field.getValue()));
        }
        plan.add(new Change(networkFile, 0, Collections.singletonMap("options", network)));

        Set<String> favorites = new LinkedHashSet<>(localConfig.strings("", "fav"));
        favorites.addAll(summary.favorites);
        Map<String, String> local = new LinkedHashMap<>();
        if (!summary.lastPeerId.isEmpty()) local.put("remote_id", TomlSections.quote(summary.lastPeerId));
        local.put("fav", TomlSections.array(favorites));
        plan.add(new Change(localFile, 0, Collections.singletonMap("", local)));

        File peersDirectory = child(root, "peers");
        long totalBytes = plan.get(0).size() + plan.get(1).size();
        for (Peer peer : summary.peers) {
            Map<String, String> info = new LinkedHashMap<>();
            info.put("username", TomlSections.quote(peer.username));
            info.put("hostname", TomlSections.quote(peer.hostname));
            info.put("platform", TomlSections.quote(peer.platform));
            Map<String, Map<String, String>> edits = new LinkedHashMap<>();
            edits.put("options", Collections.singletonMap("alias", TomlSections.quote(peer.alias)));
            edits.put("info", info);

            Change change = new Change(child(peersDirectory, peerFile(peer.id)), peer.lastUsed, edits);
            totalBytes += change.size();
            if (totalBytes > MAX_RESTORE_BYTES) throw new IOException("Restore is too large");
            plan.add(change);
        }
        return plan;
    }

    private static void requireSignOutForServerChange(TomlSections network, TomlSections local,
                                                      Map<String, String> server) throws IOException {
        // The API address can be derived from the ID server when no explicit API is configured.
        if (!local.string("options", "access_token").isEmpty()
            && (!network.string("options", SERVER_KEYS[0]).equals(server.get(SERVER_KEYS[0]))
                || !network.string("options", SERVER_KEYS[2]).equals(server.get(SERVER_KEYS[2])))) {
            throw new SignOutRequiredException();
        }
    }

    private static RestoreSummary validate(JSONObject backup) throws IOException, JSONException {
        Object version = backup.get("version");
        if (backup.length() != 6 || !"rustdesk-connections".equals(backup.get("format"))
                || !Integer.valueOf(1).equals(version)) {
            throw new IOException("Unsupported connection backup");
        }
        JSONObject serverObject = backup.getJSONObject("server");
        if (serverObject.length() != SERVER_KEYS.length) throw new IOException("Invalid server settings");
        Map<String, String> server = new LinkedHashMap<>();
        for (String key : SERVER_KEYS) server.put(key, serverField(key, serverObject.get(key)));

        String lastPeerId = field(backup.get("lastPeerId"));
        if (!lastPeerId.isEmpty()) validateId(lastPeerId);
        JSONArray favoritesArray = backup.getJSONArray("favorites");
        JSONArray peersArray = backup.getJSONArray("peers");
        if (favoritesArray.length() > MAX_PEERS || peersArray.length() > MAX_PEERS) {
            throw new IOException("Too many peers in the backup");
        }
        Set<String> favorites = new LinkedHashSet<>();
        for (int index = 0; index < favoritesArray.length(); index++) {
            String id = field(favoritesArray.get(index));
            validateId(id);
            favorites.add(id);
        }
        Set<String> seen = new LinkedHashSet<>();
        List<Peer> peers = new ArrayList<>();
        for (int index = 0; index < peersArray.length(); index++) {
            JSONObject entry = peersArray.getJSONObject(index);
            if (entry.length() != 6) throw new IOException("Invalid peer information");
            String id = field(entry.get("id"));
            String file = peerFile(id);
            if (!seen.add(file)) throw new IOException("Duplicate peer in the backup");
            String platform = field(entry.get("platform"));
            Object time = entry.get("lastUsed");
            if (platform.isEmpty() || !(time instanceof Integer || time instanceof Long)) {
                throw new IOException("Invalid peer information");
            }
            long lastUsed = ((Number) time).longValue();
            if (lastUsed < 0) throw new IOException("Invalid peer timestamp");
            peers.add(new Peer(id, field(entry.get("alias")), field(entry.get("username")),
                field(entry.get("hostname")), platform, Math.min(lastUsed, System.currentTimeMillis())));
        }
        return new RestoreSummary(backup, server, lastPeerId, favorites, peers);
    }

    private static String field(Object value) throws IOException {
        if (!(value instanceof String) || ((String) value).length() > MAX_FIELD_LENGTH) {
            throw new IOException("Invalid connection backup field");
        }
        String text = (String) value;
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(text)) {
            throw new IOException("Invalid UTF-8 in the backup");
        }
        return text;
    }

    private static String serverField(String key, Object value) throws IOException {
        String text = field(value);
        for (int index = 0; index < text.length(); index++) {
            if (Character.isISOControl(text.charAt(index))) throw new IOException("Invalid server setting");
        }
        if (text.isEmpty() || key.equals("key")) return text;
        if (text.indexOf('@') >= 0) throw new IOException("Server settings cannot contain credentials");
        if (!key.equals("api-server")) {
            if (text.indexOf('?') >= 0 || text.indexOf('#') >= 0) {
                throw new IOException("Invalid server setting");
            }
            return text;
        }
        try {
            URI api = new URI(text);
            if (!("https".equalsIgnoreCase(api.getScheme()) || "http".equalsIgnoreCase(api.getScheme()))
                    || api.getRawAuthority() == null || api.getRawQuery() != null
                    || api.getRawFragment() != null || api.getRawUserInfo() != null) {
                throw new IOException("Invalid API server");
            }
            return text;
        } catch (URISyntaxException failure) {
            throw new IOException("Invalid API server");
        }
    }

    private static void validateJsonStructure(String json) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            int depth = 0;
            while (reader.peek() != JsonToken.END_DOCUMENT) {
                switch (reader.peek()) {
                    case BEGIN_ARRAY: reader.beginArray(); depth++; break;
                    case BEGIN_OBJECT: reader.beginObject(); depth++; break;
                    case END_ARRAY: reader.endArray(); depth--; break;
                    case END_OBJECT: reader.endObject(); depth--; break;
                    case NAME: reader.nextName(); break;
                    case STRING: case NUMBER: reader.nextString(); break;
                    case BOOLEAN: reader.nextBoolean(); break;
                    case NULL: reader.nextNull(); break;
                    default: throw new IOException("Invalid connection backup");
                }
                if (depth > 4) throw new IOException("Invalid connection backup structure");
            }
            if (depth != 0) throw new IOException("Incomplete connection backup");
        } catch (IllegalStateException failure) {
            throw new IOException("Invalid connection backup", failure);
        }
    }

    private static void validateId(String id) throws IOException {
        if (id.isEmpty() || id.length() > 250) throw new IOException("Invalid peer ID");
        for (int index = 0; index < id.length(); index++) {
            if (Character.isISOControl(id.charAt(index))) throw new IOException("Invalid peer ID");
        }
    }

    private static String peerFile(String id) throws IOException {
        validateId(id);
        String stem = id;
        for (int i = 0; i < id.length(); i++) {
            if ("<>:/\\|?*".indexOf(id.charAt(i)) >= 0) {
                stem = "base64_" + Base64.encodeToString(id.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                break;
            }
        }
        if (stem.equals(".") || stem.equals("..") || stem.indexOf('/') >= 0
            || stem.getBytes(StandardCharsets.UTF_8).length > 250
            || (stem.equals(id) && stem.startsWith("base64_") && stem.length() > 7)) {
            throw new IOException("Unsupported peer ID");
        }
        return stem + ".toml";
    }

    private static String decodePeerFile(String file) {
        String id = file.substring(0, file.length() - 5);
        try {
            if (id.startsWith("base64_") && id.length() > 7) {
                id = decode(Base64.decode(id.substring(7), Base64.NO_WRAP));
            }
            return file.equals(peerFile(id)) ? id : null;
        } catch (IOException | IllegalArgumentException failure) {
            return null;
        }
    }

    private static File configRoot(Context context) throws IOException {
        // Flutter's PathUtils.getDataDirectory uses this same private directory on Android.
        return context.getDir("flutter", Context.MODE_PRIVATE).getCanonicalFile();
    }

    private static File pending(Context context) {
        return new File(context.getNoBackupFilesDir(), PENDING);
    }

    private static File child(File parent, String name) throws IOException {
        File file = new File(parent, name);
        if (!file.equals(file.getCanonicalFile())) throw new IOException("Unexpected config path");
        return file;
    }

    private static TomlSections readToml(File file) throws IOException {
        byte[] bytes = readFile(file);
        return new TomlSections(bytes == null ? "" : decode(bytes));
    }

    private static byte[] readFile(File file) throws IOException {
        if (!file.exists()) return null;
        try (InputStream input = new FileInputStream(file)) {
            return readLimited(input, MAX_CONFIG_BYTES);
        }
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count > limit - output.size()) throw new IOException("Backup or settings file is too large");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String decode(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static void writeAtomically(File target, byte[] bytes) throws IOException {
        File directory = target.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create the settings directory");
        File temp = File.createTempFile(".rustdesk-restore-", ".tmp", directory);
        try {
            try (FileOutputStream output = new FileOutputStream(temp)) {
                output.write(bytes);
                output.getFD().sync();
            }
            try {
                Os.rename(temp.getPath(), target.getPath());
            } catch (ErrnoException failure) {
                throw new IOException("Cannot replace the settings file", failure);
            }
        } finally {
            if (temp.exists()) temp.delete();
        }
    }

    public static final class SignOutRequiredException extends IOException {
        private SignOutRequiredException() {
            super("Sign out of RustDesk before changing the server");
        }
    }

    public static final class RestoreSummary {
        public final int peerCount;
        public final int favoriteCount;
        public final String lastPeerId;
        public final String idServer;
        public final String relayServer;
        public final String apiServer;
        public final boolean hasServerKey;
        private final JSONObject backup;
        private final Map<String, String> server;
        private final Set<String> favorites;
        private final List<Peer> peers;

        private RestoreSummary(JSONObject backup, Map<String, String> server, String lastPeerId,
                               Set<String> favorites, List<Peer> peers) {
            this.backup = backup;
            this.server = server;
            this.lastPeerId = lastPeerId;
            this.favorites = favorites;
            this.peers = peers;
            peerCount = peers.size();
            favoriteCount = favorites.size();
            idServer = server.get(SERVER_KEYS[0]);
            relayServer = server.get(SERVER_KEYS[1]);
            apiServer = server.get(SERVER_KEYS[2]);
            hasServerKey = !server.get(SERVER_KEYS[3]).isEmpty();
        }
    }

    private static final class Peer {
        final String id;
        final String alias;
        final String username;
        final String hostname;
        final String platform;
        final long lastUsed;

        Peer(String id, String alias, String username, String hostname, String platform, long lastUsed) {
            this.id = id;
            this.alias = alias;
            this.username = username;
            this.hostname = hostname;
            this.platform = platform;
            this.lastUsed = lastUsed;
        }
    }

    private static final class Change {
        final File file;
        final byte[] before;
        final byte[] after;
        final long previousModified;
        final long modified;

        Change(File file, long lastUsed, Map<String, Map<String, String>> edits) throws IOException {
            this.file = file;
            before = readFile(file);
            previousModified = file.lastModified();
            modified = lastUsed > 0 ? Math.max(lastUsed, previousModified) : 0;
            TomlSections document = new TomlSections(before == null ? "" : decode(before));
            after = document.edit(edits).getBytes(StandardCharsets.UTF_8);
            if (after.length > MAX_CONFIG_BYTES) throw new IOException("Restored settings file is too large");
        }

        int size() {
            return after.length + (before == null ? 0 : before.length);
        }

        void rollback() {
            try {
                if (before == null) {
                    if (!file.delete()) Log.w(TAG, "Could not roll back a connection restore file");
                } else {
                    writeAtomically(file, before);
                    if (previousModified > 0) file.setLastModified(previousModified);
                }
            } catch (IOException | RuntimeException failure) {
                Log.w(TAG, "Could not roll back a connection restore file");
            }
        }
    }
}

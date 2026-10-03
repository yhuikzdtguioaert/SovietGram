package tw.nekomimi.nekogram.config;

import android.content.SharedPreferences;
import android.util.Base64;

import org.telegram.messenger.FileLog;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import tw.nekomimi.nekogram.NekoConfig;

@SuppressWarnings({"unchecked", "unused"})
public class ConfigItem {
    public static final int configTypeBool = 0;
    public static final int configTypeInt = 1;
    public static final int configTypeString = 2;
    public static final int configTypeSetInt = 3;
    public static final int configTypeMapIntInt = 4;
    public static final int configTypeLong = 5;
    public static final int configTypeFloat = 6;
    public static final int configTypeBoolLinkInt = 7;

    public final String key;
    public final int type;
    public final Object defaultValue;

    public Object value;

    /** Set on the items of {@code sovietgram.com.NaConfig}, whose defaults win over the Nagram copy's. */
    public boolean primary;

    /**
     * Every item made so far, by key. The project carries two config objects with the same keys
     * (xyz.nextalone.nagram.NaConfig, which the settings screens and most of the app read, and
     * sovietgram.com.NaConfig, which the rest reads), and each item caches its value. Without this a
     * switch flipped through one object stayed stale in the other until the app was restarted, and a
     * key nobody had touched meant one thing to the settings screen and another to the code.
     */
    private static final java.util.Map<String, java.util.List<ConfigItem>> TWINS =
            new java.util.concurrent.ConcurrentHashMap<>();

    public ConfigItem(String key, int type, Object defaultValue) {
        this.key = key;
        this.type = type;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
        TWINS.computeIfAbsent(key, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(this);
    }

    /** Hands this item's value to every other item with the same key. */
    protected void syncTwins() {
        final java.util.List<ConfigItem> twins = TWINS.get(key);
        if (twins == null || twins.size() < 2) {
            return;
        }
        for (ConfigItem twin : twins) {
            if (twin != this && twin.type == type) {
                twin.value = value;
            }
        }
    }

    /**
     * Makes every pair of items with one key agree, once both objects are loaded: what is stored wins
     * (both read it, so they already agree), and for a key nobody ever stored, the SovietGram item's
     * default is the one the user is meant to see.
     */
    public static void unifyTwins() {
        try {
            final android.content.SharedPreferences preferences = NekoConfig.getPreferences();
            for (java.util.Map.Entry<String, java.util.List<ConfigItem>> entry : TWINS.entrySet()) {
                final java.util.List<ConfigItem> twins = entry.getValue();
                if (twins.size() < 2 || preferences.contains(entry.getKey())) {
                    continue;
                }
                ConfigItem primary = null;
                for (ConfigItem twin : twins) {
                    if (twin.primary) {
                        primary = twin;
                        break;
                    }
                }
                if (primary == null) {
                    continue;
                }
                for (ConfigItem twin : twins) {
                    if (twin != primary && twin.type == primary.type) {
                        twin.value = primary.value;
                    }
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public String getKey() {
        return key;
    }

    // Read config

    public boolean Bool() {
        return (boolean) value;
    }

    public int Int() {
        return (int) value;
    }

    public Long Long() {
        return (Long) value;
    }

    public Float Float() {
        return (Float) value;
    }

    public String String() {
        return value.toString();
    }

    public HashSet<Integer> SetInt() {
        return (HashSet<Integer>) value;
    }

    public HashMap<Integer, Integer> MapIntInt() {
        return (HashMap<Integer, Integer>) value;
    }

    public boolean SetIntContains(Integer v) {
        return ((HashSet<Integer>) value).contains(v);
    }


    public void changed(Object o) {
        value = o;
        syncTwins();
    }

    // Write config
    // Note: no type checking here

    public boolean toggleConfigBool() {
        value = !this.Bool();
        saveConfig();
        return this.Bool(); // return value after toggle
    }

    public void setConfigBool(boolean v) {
        value = v;
        saveConfig();
    }

    public void setConfigInt(int v) {
        value = v;
        saveConfig();
    }

    public void setConfigLong(Long v) {
        value = v;
        saveConfig();
    }

    public void setConfigFloat(Float v) {
        value = v;
        saveConfig();
    }

    public void setConfigString(String v) {
        value = Objects.requireNonNullElse(v, "");
        saveConfig();
    }

    public void setConfigSetInt(HashSet<Integer> v) {
        value = v;
        saveConfig();
    }

    public void setConfigMapInt(HashMap<Integer, Integer> v) {
        value = v;
        saveConfig();
    }

    // save one item
    public void saveConfig() {
        synchronized (NekoConfig.sync) {
            try {
                SharedPreferences.Editor editor = NekoConfig.getPreferences().edit();

                if (this.type == configTypeBool) {
                    editor.putBoolean(this.key, (boolean) this.value);
                }
                if (this.type == configTypeInt) {
                    editor.putInt(this.key, (int) this.value);
                }
                if (this.type == configTypeLong) {
                    editor.putLong(this.key, (Long) this.value);
                }
                if (this.type == configTypeFloat) {
                    editor.putFloat(this.key, (Float) this.value);
                }
                if (this.type == configTypeString) {
                    editor.putString(this.key, this.value.toString());
                }
                if (this.type == configTypeSetInt) {
                    HashSet<String> ss = new HashSet<>();
                    for (Integer n : (Set<Integer>) this.value) {
                        ss.add(Integer.toString(n));
                    }
                    editor.putStringSet(this.key, ss);
                }
                if (this.type == configTypeMapIntInt) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ObjectOutputStream oos = new ObjectOutputStream(baos);
                    oos.writeObject(this.value);
                    oos.close();
                    editor.putString(this.key, Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT));
                }

                editor.apply();
                syncTwins();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    public Object checkConfigFromString(String value) {
        try {
            return switch (type) {
                case configTypeBool -> Boolean.parseBoolean(value);
                case configTypeInt -> Integer.parseInt(value);
                case configTypeString -> value;
                case configTypeLong -> Long.parseLong(value);
                case configTypeFloat -> Float.parseFloat(value);
                default -> null;
            };
        } catch (Exception ignored) {}
        return null;
    }
}

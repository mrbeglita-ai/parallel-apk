package dev.codex.parallel;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class CloneStore {
    record Entry(String id, String packageName, String label, int slot, boolean installed) {
        int account() {
            return slot + 1;
        }

        Entry installedCopy() {
            return new Entry(id, packageName, label, slot, true);
        }
    }

    private final SharedPreferences preferences;
    private final String prefix;

    CloneStore(Context context) {
        preferences = context.getSharedPreferences("copies", Context.MODE_PRIVATE);
        String token = preferences.getString("profilePrefix", null);
        if (token == null) {
            token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            if (!preferences.edit().putString("profilePrefix", token).commit()) {
                throw new IllegalStateException("Не удалось записать настройки приложения.");
            }
        }
        prefix = token;
    }

    String userName(int slot) {
        return "Параллель " + (slot + 1) + " · " + prefix;
    }

    List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences.getString("entries", "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                entries.add(new Entry(item.getString("id"), item.getString("package"),
                        item.getString("label"), item.getInt("slot"), item.getBoolean("installed")));
            }
        } catch (JSONException exception) {
            throw new IllegalStateException("Не удалось прочитать список копий.", exception);
        }
        return entries;
    }

    Entry find(String id) {
        for (Entry entry : entries()) {
            if (entry.id().equals(id)) return entry;
        }
        return null;
    }

    Entry nextEntry(String packageName, String label) {
        Set<Integer> occupied = new HashSet<>();
        for (Entry entry : entries()) {
            if (entry.packageName().equals(packageName)) {
                if (!entry.installed()) return entry;
                occupied.add(entry.slot());
            }
        }
        int slot = 1;
        while (occupied.contains(slot)) slot++;
        return new Entry(UUID.randomUUID().toString(), packageName, label, slot, false);
    }

    void save(Entry entry) {
        List<Entry> entries = entries();
        for (Entry saved : entries) {
            if (!saved.id().equals(entry.id()) && saved.slot() == entry.slot()
                    && saved.packageName().equals(entry.packageName())) {
                throw new IllegalStateException("Эта копия уже существует.");
            }
        }
        entries.removeIf(saved -> saved.id().equals(entry.id()));
        entries.add(entry);
        JSONArray array = new JSONArray();
        try {
            for (Entry saved : entries) {
                array.put(new JSONObject()
                        .put("id", saved.id())
                        .put("package", saved.packageName())
                        .put("label", saved.label())
                        .put("slot", saved.slot())
                        .put("installed", saved.installed()));
            }
        } catch (JSONException exception) {
            throw new IllegalStateException("Не удалось сохранить копию.", exception);
        }
        if (!preferences.edit().putString("entries", array.toString()).commit()) {
            throw new IllegalStateException("Не удалось сохранить копию.");
        }
    }
}

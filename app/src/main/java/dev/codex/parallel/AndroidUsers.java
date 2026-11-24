package dev.codex.parallel;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AndroidUsers {
    private static final Pattern USER_INFO =
            Pattern.compile("UserInfo\\{(\\d+):([^\\r\\n]*):[0-9a-fA-F]+\\}");
    private static final Pattern LAST_NUMBER = Pattern.compile("(\\d+)\\s*$");

    record User(int id, String name) {}

    private final IShellService service;

    AndroidUsers(IShellService service) {
        this.service = service;
    }

    private String run(String... args) throws Exception {
        JSONObject result = new JSONObject(service.run(args));
        String output = result.optString("output").trim();
        if (result.getBoolean("ok")) return output;

        String error = result.optString("error", "Android отклонил операцию.");
        if (!output.trim().isEmpty() && !error.contains(output)) error += "\n\n" + output;
        if (error.length() > 900) error = error.substring(0, 900) + "…";
        throw new IllegalStateException(error);
    }

    List<User> users() throws Exception {
        String output = run("list-users");
        Matcher matcher = USER_INFO.matcher(output);
        List<User> users = new ArrayList<>();
        while (matcher.find()) {
            users.add(new User(Integer.parseInt(matcher.group(1)), matcher.group(2)));
        }
        if (users.isEmpty()) {
            throw new IllegalStateException("Не удалось прочитать список пользователей Android.");
        }
        return users;
    }

    int maxUsers() throws Exception {
        Matcher matcher = LAST_NUMBER.matcher(run("max-users"));
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    User find(String name) throws Exception {
        for (User user : users()) {
            if (user.id() > 0 && user.name().equals(name)) return user;
        }
        return null;
    }

    void install(String name, String packageName) throws Exception {
        User user = find(name);
        if (user == null) {
            run("create-user", name);
            user = find(name);
            if (user == null) {
                throw new IllegalStateException("Android не вернул созданного пользователя.");
            }
        }
        run("install", Integer.toString(user.id()), packageName);
    }

    void open(String name, String packageName, boolean launchApp) throws Exception {
        User user = find(name);
        if (user == null) {
            throw new IllegalStateException("Пользователь Android удалён или переименован. Откройте карточку копии и повторите установку.");
        }
        String id = Integer.toString(user.id());
        if (launchApp) run("open", id, packageName);
        else run("switch-user", id);
    }
}

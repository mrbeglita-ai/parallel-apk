package dev.codex.parallel;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

public final class ShellService extends IShellService.Stub {
    private static final long QUERY_TIMEOUT_MS = 12_000;
    private static final long CHANGE_TIMEOUT_MS = 60_000;
    private static final long SWITCH_TIMEOUT_MS = 90_000;
    private static final long UNLOCK_TIMEOUT_MS = 45_000;
    private static final long LAUNCH_TIMEOUT_MS = 45_000;
    private static final int MAX_OUTPUT_BYTES = 32_768;
    private static final Pattern USER_ID = Pattern.compile("[0-9]{1,10}");
    private static final Pattern PACKAGE_NAME =
            Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)*");

    private final int appUid;

    public ShellService() {
        appUid = -1;
    }

    public ShellService(Context context) {
        appUid = context.getApplicationInfo().uid;
    }

    @Override
    public synchronized String run(String[] args) {
        if (appUid < 0) {
            return response("", "Для этого приложения требуется Shizuku 13 или новее.");
        }
        if (Binder.getCallingUid() != appUid) {
            return response("", "У вызывающего приложения нет доступа к этой службе.");
        }
        try {
            return response(runOperation(args), null);
        } catch (OperationException e) {
            return response(e.output, e.getMessage());
        } catch (IllegalArgumentException e) {
            return response("", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return response("", "Операция прервана.");
        }
    }

    @Override
    public void destroy() {
        if (Binder.getCallingUid() == android.os.Process.myUid()) {
            System.exit(0);
        }
    }

    private String runOperation(String[] args) throws OperationException, InterruptedException {
        if (args == null || args.length == 0 || args[0] == null) {
            throw new IllegalArgumentException("Укажите операцию.");
        }
        return switch (args[0]) {
            case "max-users" -> {
                requireCount(args, 1);
                yield command("Получение лимита пользователей", QUERY_TIMEOUT_MS,
                        "package", "get-max-users");
            }
            case "list-users" -> {
                requireCount(args, 1);
                yield command("Получение списка пользователей", QUERY_TIMEOUT_MS,
                        "package", "list", "users");
            }
            case "current-user" -> {
                requireCount(args, 1);
                yield currentUser();
            }
            case "create-user" -> {
                requireCount(args, 2);
                yield command("Создание пользователя", CHANGE_TIMEOUT_MS,
                        "package", "create-user", userName(args[1]));
            }
            case "install" -> {
                requireCount(args, 3);
                String userId = userId(args[1]);
                if ("0".equals(userId)) {
                    throw new IllegalArgumentException("Выберите дополнительного пользователя.");
                }
                yield install(userId, packageName(args[2]));
            }
            case "start-user" -> {
                requireCount(args, 2);
                yield command("Запуск пользователя", CHANGE_TIMEOUT_MS,
                        "activity", "start-user", userId(args[1]));
            }
            case "switch-user" -> {
                requireCount(args, 2);
                yield switchUser(userId(args[1]));
            }
            case "launch", "open" -> {
                requireCount(args, 3);
                yield open(userId(args[1]), packageName(args[2]), "open".equals(args[0]));
            }
            default -> throw new IllegalArgumentException("Неизвестная операция.");
        };
    }

    private String install(String userId, String packageName)
            throws OperationException, InterruptedException {
        String installed = command("Добавление приложения", CHANGE_TIMEOUT_MS, "package",
                "install-existing", "--user", userId, "--restrict-permissions", packageName);
        String paths = command("Проверка установки", QUERY_TIMEOUT_MS, "package", "path",
                "--user", userId, packageName);
        if (!paths.startsWith("package:")) {
            throw new OperationException("Android не подтвердил установку приложения.", paths);
        }
        return installed;
    }

    private String currentUser() throws OperationException, InterruptedException {
        String output = command("Определение активного пользователя", QUERY_TIMEOUT_MS,
                "activity", "get-current-user");
        if (!USER_ID.matcher(output).matches()) {
            throw new OperationException("Android вернул неизвестный идентификатор.", output);
        }
        return output;
    }

    private String switchUser(String userId) throws OperationException, InterruptedException {
        if (userId.equals(currentUser())) {
            return userId;
        }
        try {
            command("Переключение пользователя", SWITCH_TIMEOUT_MS,
                    "activity", "switch-user", "-w", userId);
        } catch (OperationException e) {
            if (e.output.toLowerCase(Locale.ROOT).contains("unknown option")
                    && e.output.contains("-w")) {
                command("Переключение пользователя", QUERY_TIMEOUT_MS,
                        "activity", "switch-user", userId);
            } else if (!userId.equals(currentUser())) {
                throw e;
            }
        }
        long deadline = SystemClock.elapsedRealtime() + QUERY_TIMEOUT_MS;
        do {
            if (userId.equals(currentUser())) {
                return userId;
            }
            Thread.sleep(250);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new OperationException("Android не подтвердил переключение пользователя.", "");
    }

    private void awaitUserUnlock(String userId)
            throws OperationException, InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + UNLOCK_TIMEOUT_MS;
        long remaining = UNLOCK_TIMEOUT_MS;
        while (remaining > 0) {
            String state = command("Проверка готовности пользователя",
                    Math.min(remaining, QUERY_TIMEOUT_MS),
                    "activity", "get-started-user-state", userId);
            if ("RUNNING_UNLOCKED".equals(state)) {
                return;
            }
            Thread.sleep(1_000);
            remaining = deadline - SystemClock.elapsedRealtime();
        }
        throw new OperationException("Android ещё готовит пользователя. "
                + "Завершите настройку или разблокируйте пользователя, затем откройте приложение.", "");
    }

    private String open(String userId, String packageName, boolean switchIfNeeded)
            throws OperationException, InterruptedException {
        String component = resolveLauncher(userId, packageName);
        if (switchIfNeeded) {
            switchUser(userId);
        }
        awaitUserUnlock(userId);
        if (!userId.equals(currentUser())) {
            throw new OperationException("Сначала переключитесь на нужного пользователя.", "");
        }
        String output = command("Открытие приложения", LAUNCH_TIMEOUT_MS,
                "activity", "start", "-W", "--user", userId,
                "-a", Intent.ACTION_MAIN, "-c", Intent.CATEGORY_LAUNCHER,
                "-f", "0x10200000", "-n", component);
        if (!output.contains("Status: ok") || output.contains("current activity is being kept")
                || output.contains("should be handled by the caller")) {
            throw new OperationException("Android не подтвердил запуск приложения.", output);
        }
        return output;
    }

    private String resolveLauncher(String userId, String packageName)
            throws OperationException, InterruptedException {
        int directBootFlags =
                PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
        String output = command("Поиск точки запуска", QUERY_TIMEOUT_MS, "package",
                "query-activities", "--components", "--user", userId,
                "--query-flags", Integer.toString(directBootFlags),
                "-a", Intent.ACTION_MAIN, "-c", Intent.CATEGORY_LAUNCHER, "-p", packageName);
        for (String line : output.split("\\R")) {
            ComponentName component = ComponentName.unflattenFromString(line.trim());
            if (component != null && packageName.equals(component.getPackageName())
                    && validClassName(component.getClassName())) {
                return component.flattenToShortString();
            }
        }
        throw new OperationException("Не найдена точка запуска приложения для этого пользователя.",
                output);
    }

    private static boolean validClassName(String name) {
        for (String segment : name.split("\\.", -1)) {
            if (segment.isEmpty() || !Character.isJavaIdentifierStart(segment.codePointAt(0))) {
                return false;
            }
            if (!segment.codePoints().allMatch(Character::isJavaIdentifierPart)) {
                return false;
            }
        }
        return true;
    }

    private static String command(String stage, long timeoutMs, String... args)
            throws OperationException, InterruptedException {
        Process process;
        String[] argv = new String[args.length + 1];
        argv[0] = "/system/bin/cmd";
        System.arraycopy(args, 0, argv, 1, args.length);
        try {
            process = new ProcessBuilder(argv).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new OperationException(stage + ". Не удалось запустить команду Android.",
                    e.toString());
        }
        FutureTask<String> reader = new FutureTask<>(() -> readOutput(process.getInputStream()));
        Thread readerThread = new Thread(reader, "android-command-output");
        readerThread.setDaemon(true);
        readerThread.start();
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new TimeoutException();
            }
            String output = reader.get(Math.max(1, deadline - SystemClock.elapsedRealtime()),
                    TimeUnit.MILLISECONDS);
            if (process.exitValue() != 0 || hasCommandError(output)) {
                throw new OperationException(stage + ". Android отклонил операцию.", output);
            }
            return output;
        } catch (TimeoutException e) {
            throw new OperationException(stage + ". Время ожидания истекло.", "");
        } catch (ExecutionException e) {
            throw new OperationException(stage + ". Не удалось прочитать ответ Android.",
                    e.getCause().toString());
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            reader.cancel(true);
        }
    }

    private static String readOutput(InputStream input) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            boolean truncated = false;
            while ((count = stream.read(buffer)) != -1) {
                int kept = Math.min(count, MAX_OUTPUT_BYTES - output.size());
                output.write(buffer, 0, kept);
                truncated |= kept < count;
            }
            String text = new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
            return truncated ? text + "\n[Вывод сокращён]" : text;
        }
    }

    private static boolean hasCommandError(String output) {
        for (String line : output.split("\\R")) {
            String normalized = line.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("error:") || normalized.startsWith("failure")
                    || normalized.startsWith("exception occurred")) {
                return true;
            }
        }
        return false;
    }

    private static void requireCount(String[] args, int count) {
        if (args.length != count) {
            throw new IllegalArgumentException("Неверное число параметров операции.");
        }
    }

    private static String userId(String value) {
        if (value == null || !USER_ID.matcher(value).matches()
                || Long.parseLong(value) > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Неверный идентификатор пользователя.");
        }
        return Integer.toString(Integer.parseInt(value));
    }

    private static String packageName(String value) {
        if (value == null || value.length() > 255 || !PACKAGE_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("Неверное имя пакета.");
        }
        return value;
    }

    private static String userName(String value) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty() || name.length() > 64 || name.startsWith("-")
                || name.codePoints().anyMatch(character -> Character.isISOControl(character)
                        || character == ':' || character == '{' || character == '}'
                        || character == 0x2028 || character == 0x2029)) {
            throw new IllegalArgumentException(
                    "Имя пользователя должно содержать до 64 символов без двоеточий, фигурных скобок и начального дефиса.");
        }
        return name;
    }

    private static String response(String output, String error) {
        try {
            JSONObject result = new JSONObject();
            result.put("ok", error == null);
            result.put("output", output);
            if (error != null) {
                result.put("error", error);
            }
            return result.toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class OperationException extends Exception {
        private final String output;

        private OperationException(String message, String output) {
            super(message);
            this.output = output;
        }
    }
}

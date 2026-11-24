package dev.codex.parallel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public final class MainActivity extends Activity {
    private static final String OPEN_COPY = "dev.codex.parallel.OPEN_COPY";
    private static final String COPY_ID = "copy_id";
    private static final int PERMISSION_REQUEST = 31;

    private static final int PAGE = 0xfff3f7f5;
    private static final int INK = 0xff112a22;
    private static final int MUTED = 0xff52685f;
    private static final int GREEN = 0xff006a56;
    private static final int MINT = 0xffd9f1e7;
    private static final int LINE = 0xffdbe6df;
    private static final int WHITE = Color.WHITE;

    private static final ExecutorService OPERATIONS = Executors.newSingleThreadExecutor();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable bindTimeout = () -> {
        connecting = false;
        connectionFailed = true;
        render();
    };

    private final Shizuku.OnBinderReceivedListener binderReceived =
            () -> runOnUiThread(this::connectIfAuthorized);
    private final Shizuku.OnBinderDeadListener binderDead = () -> runOnUiThread(() -> {
        main.removeCallbacks(bindTimeout);
        bridge = null;
        connecting = false;
        connectionFailed = false;
        maxUsers = -1;
        render();
    });
    private final Shizuku.OnRequestPermissionResultListener permissionResult =
            (requestCode, grantResult) -> runOnUiThread(() -> {
                if (requestCode == PERMISSION_REQUEST) {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) connectIfAuthorized();
                    else render();
                }
            });

    private CloneStore store;
    private Shizuku.UserServiceArgs serviceArgs;
    private volatile IShellService bridge;
    private boolean connecting;
    private boolean connectionFailed;
    private boolean bindingRequested;
    private String busy;
    private String pendingOpen;
    private int maxUsers = -1;
    private LinearLayout content;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            main.removeCallbacks(bindTimeout);
            bridge = IShellService.Stub.asInterface(binder);
            connecting = false;
            connectionFailed = false;
            render();
            openPendingCopy();
            readUserLimit();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bridge = null;
            maxUsers = -1;
            connecting = false;
            render();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new CloneStore(this);
        serviceArgs = new Shizuku.UserServiceArgs(new ComponentName(this, ShellService.class))
                .daemon(true)
                .processNameSuffix("bridge")
                .version(2);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(PAGE);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets safe = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });
        content = column();
        content.setPadding(dp(22), dp(20), dp(22), dp(26));
        scroll.addView(content);
        setContentView(scroll);
        getWindow().setDecorFitsSystemWindows(false);
        scroll.post(() -> {
            WindowInsetsController controller = scroll.getWindowInsetsController();
            if (controller != null) {
                int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(lightBars, lightBars);
            }
        });

        acceptShortcut(getIntent());
        Shizuku.addBinderDeadListener(binderDead);
        Shizuku.addRequestPermissionResultListener(permissionResult);
        Shizuku.addBinderReceivedListenerSticky(binderReceived);
        render();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        acceptShortcut(intent);
        openPendingCopy();
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
        connectIfAuthorized();
        openPendingCopy();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(bindTimeout);
        Shizuku.removeBinderReceivedListener(binderReceived);
        Shizuku.removeBinderDeadListener(binderDead);
        Shizuku.removeRequestPermissionResultListener(permissionResult);
        if (bindingRequested) {
            try {
                Shizuku.unbindUserService(serviceArgs, serviceConnection, false);
            } catch (RuntimeException ignored) {
                // The manager may already have stopped.
            }
        }
        super.onDestroy();
    }

    private void acceptShortcut(Intent intent) {
        if (!OPEN_COPY.equals(intent.getAction())) return;
        String id = intent.getStringExtra(COPY_ID);
        CloneStore.Entry entry = id == null ? null : store.find(id);
        if (entry != null && entry.installed()) pendingOpen = id;
        else toast("Эта копия недоступна.");
        setIntent(new Intent(Intent.ACTION_MAIN).setClass(this, MainActivity.class));
    }

    private void openPendingCopy() {
        if (pendingOpen == null || bridge == null || busy != null) return;
        CloneStore.Entry entry = store.find(pendingOpen);
        pendingOpen = null;
        if (entry != null && entry.installed()) openCopy(entry, true);
    }

    private boolean authorized() {
        try {
            return Shizuku.pingBinder() && Shizuku.getVersion() >= 13
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void connectIfAuthorized() {
        if (isDestroyed() || bridge != null || connecting || connectionFailed || !authorized()) {
            render();
            return;
        }
        connecting = true;
        try {
            Shizuku.bindUserService(serviceArgs, serviceConnection);
            bindingRequested = true;
            main.postDelayed(bindTimeout, 35_000L);
        } catch (RuntimeException exception) {
            connecting = false;
            connectionFailed = true;
        }
        render();
    }

    private void connectButton() {
        if (!Shizuku.pingBinder()) {
            Intent shizuku = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (shizuku != null) startActivity(shizuku);
            else new AlertDialog.Builder(this)
                    .setTitle("Настройка Shizuku")
                    .setMessage("Установите Shizuku, запустите его через беспроводную отладку Android и вернитесь в Параллель.")
                    .setPositiveButton("Открыть инструкцию", (dialog, button) -> openGuide())
                    .setNegativeButton("Закрыть", null)
                    .show();
            return;
        }
        try {
            if (Shizuku.getVersion() < 13) {
                toast("Обновите Shizuku до версии 13 или новее.");
                return;
            }
            if (authorized()) {
                connectionFailed = false;
                connectIfAuthorized();
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                new AlertDialog.Builder(this).setTitle("Разрешение Shizuku")
                        .setMessage("Откройте Shizuku → Авторизованные приложения и разрешите доступ приложению «Параллель».")
                        .setPositiveButton("Понятно", null).show();
            } else {
                Shizuku.requestPermission(PERMISSION_REQUEST);
            }
        } catch (RuntimeException exception) {
            failed("Не удалось подключиться к Shizuku", exception.getMessage(), null);
        }
    }

    private void openUserSettings() {
        try {
            startActivity(new Intent("android.settings.USER_SETTINGS"));
        } catch (ActivityNotFoundException exception) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void openGuide() {
        Intent guide = new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/guide/setup/"));
        try {
            startActivity(guide);
        } catch (RuntimeException exception) {
            toast("Инструкция доступна на shizuku.rikka.app.");
        }
    }

    private void readUserLimit() {
        IShellService activeBridge = bridge;
        if (activeBridge == null) return;
        OPERATIONS.execute(() -> {
            int result;
            try {
                result = new AndroidUsers(activeBridge).maxUsers();
            } catch (Exception exception) {
                result = -1;
            }
            int userLimit = result;
            main.post(() -> {
                if (!isDestroyed() && bridge == activeBridge) {
                    maxUsers = userLimit;
                    render();
                }
            });
        });
    }

    private void suggestCopy(InstalledApp app) {
        CloneStore.Entry entry = store.nextEntry(app.packageName(), app.label());
        new AlertDialog.Builder(this)
                .setTitle("Создать копию «" + app.label() + "»?")
                .setMessage("Аккаунт " + entry.account() + " получит отдельные данные. "
                        + "Если этого пользователя Android ещё нет, приложение создаст его.")
                .setPositiveButton("Создать", (dialog, button) -> installCopy(entry))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void installCopy(CloneStore.Entry entry) {
        String userName = store.userName(entry.slot());
        runOperation("Устанавливаем копию…", users -> {
            store.save(entry);
            users.install(userName, entry.packageName());
            store.save(entry.installedCopy());
        }, () -> toast("Копия готова. Можно добавить ярлык."), message -> {
            failed("Не удалось установить копию", message, null);
        });
    }

    private void openCopy(CloneStore.Entry entry, boolean launchApp) {
        runOperation("Переключаем пользователя Android…",
                users -> users.open(store.userName(entry.slot()), entry.packageName(), launchApp),
                () -> { },
                message -> failed(launchApp ? "Не удалось открыть копию" : "Не удалось переключить пользователя",
                        message, launchApp ? entry : null));
    }

    @FunctionalInterface
    private interface ShellOperation {
        void run(AndroidUsers users) throws Exception;
    }

    @FunctionalInterface
    private interface OnError {
        void accept(String message);
    }

    private void runOperation(String message, ShellOperation operation, Runnable success, OnError error) {
        if (busy != null) {
            toast("Дождитесь завершения текущей операции.");
            return;
        }
        IShellService activeBridge = bridge;
        if (activeBridge == null) {
            connectButton();
            return;
        }
        busy = message;
        render();
        OPERATIONS.execute(() -> {
            String failure = null;
            try {
                operation.run(new AndroidUsers(activeBridge));
            } catch (Exception exception) {
                failure = exception.getMessage();
                if (failure == null || failure.trim().isEmpty()) failure = "Android не завершил операцию.";
            }
            String result = failure;
            main.post(() -> {
                if (isDestroyed()) return;
                busy = null;
                render();
                if (result == null) success.run();
                else error.accept(result);
                openPendingCopy();
            });
        });
    }

    private void failed(String title, String message, CloneStore.Entry switchTarget) {
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message == null || message.trim().isEmpty() ? "Попробуйте ещё раз." : message)
                .setNegativeButton("Закрыть", null);
        if (switchTarget != null) {
            dialog.setPositiveButton("Переключить пользователя",
                    (view, which) -> openCopy(switchTarget, false));
        }
        dialog.show();
    }

    private void pinCopy(CloneStore.Entry entry) {
        ShortcutManager shortcuts = getSystemService(ShortcutManager.class);
        if (shortcuts == null || !shortcuts.isRequestPinShortcutSupported()) {
            toast("Этот лаунчер не поддерживает добавление ярлыков.");
            return;
        }
        String label = entry.account() + " · " + entry.label();
        Intent intent = new Intent(OPEN_COPY).setClass(this, MainActivity.class)
                .putExtra(COPY_ID, entry.id());
        ShortcutInfo shortcut = new ShortcutInfo.Builder(this, entry.id())
                .setShortLabel(label)
                .setIcon(copyIcon(entry))
                .setIntent(intent)
                .build();
        try {
            if (!shortcuts.requestPinShortcut(shortcut, null)) {
                toast("Лаунчер отклонил запрос на добавление ярлыка.");
            }
        } catch (RuntimeException exception) {
            failed("Не удалось добавить ярлык", exception.getMessage(), null);
        }
    }

    private Icon copyIcon(CloneStore.Entry entry) {
        int size = 108;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(WHITE);
        canvas.drawRoundRect(new RectF(0, 0, size, size), 23, 23, paint);
        Drawable original = icon(entry.packageName());
        original.setBounds(11, 11, 89, 89);
        original.draw(canvas);
        paint.setColor(GREEN);
        canvas.drawCircle(82, 26, 23, paint);
        paint.setColor(WHITE);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(entry.account() > 9 ? 19 : 23);
        canvas.drawText(Integer.toString(entry.account()), 82, 26 - (paint.ascent() + paint.descent()) / 2, paint);
        return Icon.createWithBitmap(bitmap);
    }

    private record InstalledApp(String packageName, String label) {}

    private List<InstalledApp> installedApps() {
        PackageManager packages = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Map<String, InstalledApp> byPackage = new LinkedHashMap<>();
        for (ResolveInfo info : packages.queryIntentActivities(launcher, 0)) {
            String name = info.activityInfo.packageName;
            if (name.equals(getPackageName()) || name.equals("moe.shizuku.privileged.api")) continue;
            CharSequence label = info.activityInfo.applicationInfo.loadLabel(packages);
            byPackage.putIfAbsent(name, new InstalledApp(name, label.toString()));
        }
        List<InstalledApp> apps = new ArrayList<>(byPackage.values());
        Collator collator = Collator.getInstance();
        apps.sort((left, right) -> collator.compare(left.label(), right.label()));
        return apps;
    }

    private void showApps() {
        List<InstalledApp> all = installedApps();
        List<InstalledApp> shown = new ArrayList<>(all);
        LinearLayout panel = column();
        panel.setPadding(dp(16), dp(8), dp(16), 0);
        EditText search = new EditText(this);
        search.setHint("Название приложения");
        search.setSingleLine(true);
        panel.addView(search, new LinearLayout.LayoutParams(-1, -2));

        TextView empty = label("Приложения не найдены", 15, MUTED);
        empty.setPadding(dp(12), dp(18), dp(12), 0);
        empty.setVisibility(View.GONE);
        panel.addView(empty);
        ListView list = new ListView(this);
        int listHeight = Math.min(420, Math.max(130,
                getResources().getConfiguration().screenHeightDp - 280));
        panel.addView(list, new LinearLayout.LayoutParams(-1, dp(listHeight)));
        list.setEmptyView(empty);
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public InstalledApp getItem(int position) { return shown.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View view, ViewGroup parent) {
                InstalledApp app = getItem(position);
                return appRow(app.packageName(), app.label(), app.packageName());
            }
        };
        list.setAdapter(adapter);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Выберите приложение")
                .setView(panel).setNegativeButton("Отмена", null).create();
        list.setOnItemClickListener((parent, item, position, id) -> {
            InstalledApp app = shown.get(position);
            dialog.dismiss();
            suggestCopy(app);
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                String query = text.toString().trim().toLowerCase(Locale.ROOT);
                shown.clear();
                for (InstalledApp app : all) {
                    if (app.label().toLowerCase(Locale.ROOT).contains(query)
                            || app.packageName().toLowerCase(Locale.ROOT).contains(query)) {
                        shown.add(app);
                    }
                }
                adapter.notifyDataSetChanged();
            }
            @Override public void afterTextChanged(Editable text) {}
        });
        dialog.show();
    }

    private void render() {
        if (content == null || isDestroyed()) return;
        content.removeAllViews();
        LinearLayout heading = row();
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_parallel);
        logo.setBackground(background(GREEN, 15, 0));
        logo.setClipToOutline(true);
        heading.addView(logo, new LinearLayout.LayoutParams(dp(47), dp(47)));
        TextView title = label("Параллель", 29, INK);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams titleLayout = new LinearLayout.LayoutParams(-2, -2);
        titleLayout.setMarginStart(dp(13));
        heading.addView(title, titleLayout);
        add(content, heading, 0);
        add(content, label("Копии приложений для разных аккаунтов", 15, MUTED), 12);

        LinearLayout connection = card();
        boolean ready = bridge != null;
        TextView status = label(ready ? "Shizuku подключён" : "Подключение к Android", 17, INK);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        add(connection, status, 0);
        String description;
        if (busy != null) description = busy;
        else if (ready && maxUsers == 1) {
            description = "Прошивка сообщает лимит в одного пользователя. Создание копий может быть недоступно.";
        } else if (ready && maxUsers > 1) {
            description = "Лимит пользователей по данным Android — " + maxUsers
                    + ". Ярлыки переключают пользователя.";
        } else if (ready) {
            description = "Копии открываются после переключения пользователя.";
        } else if (connecting) {
            description = "Подключаем Shizuku…";
        } else if (connectionFailed) {
            description = "Связь с Shizuku не установилась. Проверьте, что сервис запущен.";
        } else {
            description = "Запустите Shizuku через беспроводную отладку и разрешите доступ.";
        }
        add(connection, label(description, 14, MUTED), 6);
        if (!ready) {
            Button connect = button("Подключить Shizuku", true, this::connectButton);
            connect.setEnabled(!connecting && busy == null);
            add(connection, connect, 12);
        } else {
            Button settings = button("Пользователи Android", false, this::openUserSettings);
            settings.setEnabled(busy == null);
            add(connection, settings, 12);
        }
        add(content, connection, 22);

        Button create = button("+  Создать копию", true, this::showApps);
        create.setEnabled(ready && busy == null);
        add(content, create, 16);

        List<CloneStore.Entry> entries = store.entries();
        entries.sort(Comparator.comparing(CloneStore.Entry::label, String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(CloneStore.Entry::slot));
        TextView listTitle = label("Мои копии", 21, INK);
        listTitle.setTypeface(Typeface.DEFAULT_BOLD);
        add(content, listTitle, 27);
        if (entries.isEmpty()) {
            LinearLayout empty = card();
            add(empty, label("Копий пока нет", 16, INK), 0);
            add(empty, label("Выберите приложение. Чтобы добавить к нему ещё один аккаунт, выберите его снова.", 14, MUTED), 6);
            add(content, empty, 10);
        }
        for (CloneStore.Entry entry : entries) {
            LinearLayout item = card();
            String note = "Аккаунт " + entry.account();
            if (!entry.installed()) note += " · установка не завершена";
            add(item, appRow(entry.packageName(), entry.label(), note), 0);
            LinearLayout actions = row();
            if (entry.installed()) {
                Button open = button("Открыть", true, () -> openCopy(entry, true));
                open.setEnabled(ready && busy == null);
                addAction(actions, open, 0);
                addAction(actions, button("Ярлык", false, () -> pinCopy(entry)), 8);
                Button more = button("⋯", false, () -> showCopyOptions(entry));
                more.setContentDescription("Действия для копии " + entry.label());
                more.setEnabled(busy == null);
                LinearLayout.LayoutParams moreLayout = new LinearLayout.LayoutParams(dp(52), -2);
                moreLayout.setMarginStart(dp(8));
                actions.addView(more, moreLayout);
            } else {
                Button retry = button("Повторить установку", false, () -> installCopy(entry));
                retry.setEnabled(ready && busy == null);
                addAction(actions, retry, 0);
            }
            add(item, actions, 12);
            add(content, item, 10);
        }

        if (!entries.isEmpty()) {
            add(content, label("Чтобы вернуться к основному аккаунту Android, откройте быстрые настройки и выберите своего пользователя.", 13, MUTED), 19);
        }
    }

    private void showCopyOptions(CloneStore.Entry entry) {
        if (bridge == null) {
            connectButton();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(entry.label() + " · аккаунт " + entry.account())
                .setItems(new String[]{"Переключить пользователя", "Повторить установку"},
                        (dialog, position) -> {
                            if (position == 0) openCopy(entry, false);
                            else installCopy(entry);
                        }).show();
    }

    private LinearLayout appRow(String packageName, String title, String subtitle) {
        LinearLayout wrapper = row();
        wrapper.setMinimumHeight(dp(52));
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(icon(packageName));
        wrapper.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout texts = column();
        TextView name = label(title, 16, INK);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        add(texts, name, 0);
        add(texts, label(subtitle, 13, MUTED), 3);
        LinearLayout.LayoutParams textLayout = new LinearLayout.LayoutParams(0, -2, 1);
        textLayout.setMarginStart(dp(12));
        wrapper.addView(texts, textLayout);
        return wrapper;
    }

    private Drawable icon(String packageName) {
        try {
            return getPackageManager().getApplicationIcon(packageName);
        } catch (PackageManager.NameNotFoundException exception) {
            return getDrawable(R.drawable.ic_parallel);
        }
    }

    private LinearLayout card() {
        LinearLayout layout = column();
        layout.setBackground(background(WHITE, 19, LINE));
        layout.setPadding(dp(16), dp(15), dp(16), dp(15));
        return layout;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button button(String text, boolean filled, Runnable action) {
        Button button = new Button(this, null, android.R.attr.borderlessButtonStyle);
        int normalColor = filled ? GREEN : MINT;
        int foreground = filled ? WHITE : GREEN;
        int[][] states = {{-android.R.attr.state_enabled}, {}};
        android.graphics.drawable.StateListDrawable surface = new android.graphics.drawable.StateListDrawable();
        surface.addState(states[0], background(LINE, 12, 0));
        surface.addState(states[1], background(normalColor, 12, 0));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x30006a56),
                surface, null));
        button.setTextColor(new ColorStateList(states, new int[]{MUTED, foreground}));
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setMinimumWidth(0);
        button.setMinWidth(0);
        button.setMinHeight(dp(48));
        button.setPadding(dp(11), dp(6), dp(11), dp(6));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private GradientDrawable background(int fill, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        if (stroke != 0) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private void add(LinearLayout parent, View child, int marginTop) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(marginTop);
        parent.addView(child, layout);
    }

    private void addAction(LinearLayout parent, Button action, int marginStart) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, -2, 1);
        layout.setMarginStart(dp(marginStart));
        parent.addView(action, layout);
    }

    private int dp(int amount) {
        return Math.round(amount * getResources().getDisplayMetrics().density);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}

package com.twinsanityevolved.game;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.StatFs;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

import static com.twinsanityevolved.game.Lang.T;

/**
 * The app's entry (launcher icon), in the app's main process, with no native code and no SDL. A console-style menu for a
 * handheld's OLED screen: pure black, white text, the selected row framed in white, no gradient, glow or
 * animation. Five tabs: Jogar (the disc image, its state, JOGAR), Gráficos, Áudio, Controles, Sistema (language,
 * restoring, the diagnostics, leaving the app).
 *
 * The settings are the game's own, in ps2r.ini (Settings.java): the game's menus (Options > graphic options, Options >
 * android settings) and this screen edit the same keys, and every change here is written at once.
 *
 * Controller first (Nav.java holds the rules, tested on the CI): the d-pad, its hat and the left stick move between the
 * rows -- one step at the press, then a steady repeat after a delay; the stick with a deadzone and hysteresis. Left /
 * right change the selected setting in place, A chooses the selected row, B goes back (to Jogar, then asks to leave),
 * LB / RB change tab (each tab keeps its selected row), START plays from the Jogar tab (never under a dialog, the file
 * picker, a copy or a start). The rows are not Android-focusable: the selection is this screen's own, so touch and the
 * controller always agree -- a tap selects the row and acts on it.
 */
public final class LauncherActivity extends Activity {
    private static final int REQUEST_PICK_ISO = 41;
    private static final int REQUEST_EXPORT_SAVES = 42;
    private static final int REQUEST_IMPORT_SAVES = 43;
    private final AtomicBoolean copyCancel = new AtomicBoolean(false);
    private boolean copying;
    private boolean starting;
    private boolean pickerOpen;

    // the palette: OLED black, white, a neutral grey
    static final int BG = 0xFF000000, FG = 0xFFFFFFFF, SUBTLE = 0xFF8C8C8C, LINE = 0xFF1A1A1A, DIM = 0xFF5A5A5A;

    static final int TAB_PLAY = Nav.TAB_PLAY, TAB_GRAPHICS = 1, TAB_AUDIO = 2, TAB_CONTROLS = 3, TAB_SAVES = 4, TAB_SYSTEM = 5;
    static final String[] TAB_NAMES = {"Jogar", "Gráficos", "Áudio", "Controles", "Saves", "Sistema"};

    // the screen
    private LinearLayout page;
    private ScrollView pageScroll;
    private final List<TextView> tabLabels = new ArrayList<>();
    private final List<View> tabLines = new ArrayList<>();
    private int currentTab = TAB_PLAY;
    private final int[] tabSelection = new int[TAB_NAMES.length];
    private final List<Item> items = new ArrayList<>();
    private int selected = -1;
    private TextView status;                    // Sistema's diagnostic text (null on the other tabs)
    private TextView isoName, isoState, lastRun, playLabel;   // the Jogar tab's (null on the other tabs)
    private OledDialog openDialog;
    private Item presetItem;                    // Gráficos' preset row (null on the other tabs)

    // the controller
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Nav.Repeater dpad = new Nav.Repeater();
    private final Nav.Repeater stick = new Nav.Repeater();
    private int stickDir = Nav.NONE;
    private boolean repeatPosted;
    private final Runnable repeatTick = new Runnable() {
        @Override
        public void run() {
            repeatPosted = false;
            if (dialogOpen() || !hasWindowFocus()) {
                releaseHeld();
                return;
            }
            long now = SystemClock.uptimeMillis();
            int d = dpad.tick(now);
            if (d != Nav.NONE) navigate(d);
            int s = stick.tick(now);
            if (s != Nav.NONE) navigate(s);
            scheduleRepeat();
        }
    };

    // The audio driver a game starts with when the player chose none: OpenSL ES -- the one that played cleanly on the
    // Retroid Pocket 6 (SDL's own order, AAudio first, crackled there). Every driver stays selectable.
    static final String DEFAULT_AUDIO_DRIVER = "openslES";

    // Diagnostic mode (off by default): the next game start logs the GPU driver's messages (TE_DIAG_GL=1) and arms the
    // L3 + R3 draw capture (TE_DIAG_CAPTURE=1), logs the sticks (TE_INPUT_LOG=1) and the game's vibration commands
    // (TE_RUMBLE_LOG=1), for a report about a graphics, controls or vibration problem. Off, none of them costs anything.
    static final String[] DIAGNOSTIC_ENV = {"TE_DIAG_GL=1", "TE_DIAG_CAPTURE=1", "TE_INPUT_LOG=1", "TE_RUMBLE_LOG=1"};

    // ---- the settings this screen shows: the game's keys and words (Settings.java) ---------------------------------------
    static final class Choice {
        final String key, label, help, def;
        final String[] values, names;

        Choice(String key, String label, String help, String def, String[] values, String[] names) {
            this.key = key;
            this.label = label;
            this.help = help;
            this.def = def;
            this.values = values;
            this.names = names;
        }

        int index(String v) {
            for (int i = 0; i < values.length; i++) if (values[i].equals(v)) return i;
            for (int i = 0; i < values.length; i++) if (values[i].equals(def)) return i;
            return 0;
        }
    }

    static final Choice[] GRAPHICS = {
        new Choice(Settings.TEXTURE_FILTER, "Filtro de texturas", "Bilinear corrige os brilhos quadrados (máscara do Aku Aku, cogumelos, cachoeira)",
            "bilinear", new String[] {"title", "point", "bilinear"}, new String[] {"Original (do jogo)", "Point (pixelado)", "Bilinear — recomendado"}),
        new Choice(Settings.ANISOTROPY, "Filtragem anisotrópica",
            "Texturas do chão e paredes mais nítidas em ângulo; limitada ao máximo do driver (o log diz o aplicado)",
            "1", new String[] {"1", "2", "4", "8", "16"}, new String[] {"Desligada — padrão", "2x", "4x", "8x", "16x"}),
        new Choice(Settings.FXAA, "Antisserrilhado (FXAA)",
            "Suaviza bordas serrilhadas na imagem final; pode suavizar um pouco textos e o HUD",
            "off", new String[] {"off", "on"}, new String[] {"Desligado — padrão", "FXAA"}),
        new Choice(Settings.DITHERING, "Dithering",
            "Original: a saída de 16 bits com dithering do próprio PS2. Desligado: a imagem de 24 bits do port, mais lisa",
            "off", new String[] {"off", "original"}, new String[] {"Desligado — padrão", "Original (PS2)"}),
        new Choice(Settings.SCALE, "Resolução interna", "Mais alta: imagem mais nítida e mais trabalho para a GPU",
            "1", new String[] {"1", "2", "3", "4"}, new String[] {"1x (nativa do PS2)", "2x", "3x", "4x"}),
        new Choice(Settings.ASPECT, "Proporção da tela",
            "Widescreen com a opção widescreen do jogo ligada (Opções → Opções gráficas, fica no save) é o widescreen verdadeiro; desligada, a imagem 4:3 fica esticada",
            "16:9", new String[] {"4:3", "16:9"}, new String[] {"Original (4:3)", "Widescreen (16:9) — padrão"}),
        new Choice(Settings.FRAME_RATE, "Taxa de quadros", "Quantos quadros são mostrados por segundo; a velocidade do jogo não muda",
            "60", new String[] {"30", "60"}, new String[] {"30 FPS", "60 FPS — padrão"}),
        new Choice(Settings.VSYNC, "VSync", "Sincroniza com a tela; desligue se notar engasgos no ritmo dos quadros",
            "on", new String[] {"off", "on"}, new String[] {"Desligado", "Ligado — padrão"}),
        new Choice(Settings.FPS_COUNTER, "Contador de FPS", "Mostra o FPS no canto da tela durante o jogo",
            "off", new String[] {"off", "on"}, new String[] {"Desligado", "Ligado"}),
        new Choice(Settings.DRAW_DISTANCE, "Distância de detalhe (LOD, experimental)",
            "Mantém os modelos detalhados por mais distância, só entre os que o jogo já carregou. Exige mais da GPU",
            "original", new String[] {"original", "medium", "long", "max"},
            new String[] {"Original (1x)", "Média (2x)", "Longa (3x)", "Máxima (4x)"}),
    };
    static final Choice[] AUDIO = {
        new Choice(Settings.AUDIO_DRIVER, "Driver de áudio", "OpenSL ES é o que funcionou no Retroid Pocket 6 (vale no próximo início)",
            DEFAULT_AUDIO_DRIVER, new String[] {"openslES", "aaudio", "android", "auto"},
            new String[] {"OpenSL ES — recomendado", "AAudio", "AudioTrack (Java)", "Automático (ordem do SDL)"}),
        new Choice(Settings.AUDIO_BUFFER, "Tamanho do buffer", "Maior: mais estável, com um pouco mais de atraso (vale no próximo início)",
            "2048", new String[] {"1024", "2048", "4096"}, new String[] {"1024 (21 ms)", "2048 (43 ms) — padrão", "4096 (85 ms)"}),
    };
    static final Choice[] CONTROLS = {
        new Choice(Settings.STICK_DEADZONE, "Zona morta dos analógicos", "Quanto do centro do analógico é ignorado",
            "0.15", new String[] {"0.05", "0.10", "0.15", "0.20", "0.25"}, new String[] {"5%", "10%", "15% — padrão", "20%", "25%"}),
        new Choice(Settings.STICK_SENSITIVITY, "Sensibilidade dos analógicos", "Maior: o personagem chega à velocidade máxima com menos inclinação",
            "1.33", new String[] {"1.00", "1.33", "1.66", "2.00"}, new String[] {"100%", "133% — padrão", "166%", "200%"}),
        new Choice("android.rumble", "Vibração no jogo",
            "A vibração original do jogo (precisa da opção Vibração do jogo ligada). Automática: motor do controle, ou o do aparelho",
            "auto", new String[] {"auto", "controller", "off"}, new String[] {"Automática — padrão", "Só o motor do controle", "Desligada"}),
        new Choice(Haptics.KEY, "Vibração no launcher", "Um toque curto ao confirmar e ao trocar de aba (o motor do aparelho)",
            "on", new String[] {"on", "off"}, new String[] {"Ligada — padrão", "Desligada"}),
    };
    static final Choice LANGUAGE = new Choice(Lang.KEY, "Idioma / Language", null, "auto", new String[] {"auto", "pt-BR", "en"},
        new String[] {"Automático (idioma do sistema)", "Português (Brasil)", "English"});

    // ---- a row of a tab: what A, left / right and a tap do on it ---------------------------------------------------------
    private final class Item {
        final LinearLayout view;
        final List<TextView> main = new ArrayList<>();
        final List<TextView> sub = new ArrayList<>();
        Runnable onA;
        IntConsumer onStep;
        BooleanSupplier enabled;

        Item(LinearLayout view) {
            this.view = view;
        }

        boolean isEnabled() {
            return enabled == null || enabled.getAsBoolean();
        }
    }

    /**
     * A tab's underline: as wide as its label. (A plain View asked for its width under "wrap content" answers the whole
     * width it is offered -- the first tab then took the full row and pushed the other tabs off the screen.)
     */
    static final class UnderlineView extends View {
        UnderlineView(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int w = MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(widthSpec) : 0;
            setMeasuredDimension(w, MeasureSpec.getSize(heightSpec));
        }
    }

    // the selected row: a white frame round it (the row itself stays black, its text white)
    private android.graphics.drawable.Drawable frame() {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(BG);
        g.setStroke(dp(2), FG);
        g.setCornerRadius(dp(6));
        return g;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(T(s));   // (a text not in the table -- a file name, a status line -- stays as it is)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private View line() {
        View v = new View(this);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        return v;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Diag.installCrashHandler(this, "main");
        new Settings(this).migrate(prefs());
        Lang.init(this);
        Haptics.refresh(this);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setBackgroundColor(BG);
        if (savedInstanceState != null) {
            int[] kept = savedInstanceState.getIntArray("tab_selection");
            if (kept != null && kept.length == tabSelection.length) System.arraycopy(kept, 0, tabSelection, 0, kept.length);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(32), dp(14), dp(32), dp(10));
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);

        // ---- the tabs, the version on the right ----
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < TAB_NAMES.length; i++) {
            final int index = i;
            LinearLayout tab = new LinearLayout(this);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setPadding(0, 0, dp(28), 0);
            TextView label = text(TAB_NAMES[i], 17, SUBTLE, false);
            label.setSingleLine(true);
            label.setPadding(0, dp(6), 0, dp(6));
            tab.addView(label);
            View under = new UnderlineView(this);
            tab.addView(under, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));
            tab.setClickable(true);
            tab.setOnClickListener(v -> {
                if (index != currentTab) Haptics.tap(this, null, false);
                showTab(index);
            });
            top.addView(tab);
            tabLabels.add(label);
            tabLines.add(under);
        }
        View gap = new View(this);
        top.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));
        top.addView(text(T("versão ") + versionName(), 12, SUBTLE, false));
        root.addView(top);
        root.addView(line());

        // ---- the tab's rows ----
        pageScroll = new ScrollView(this);
        pageScroll.setVerticalScrollBarEnabled(false);
        pageScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, dp(8), 0, dp(24));
        pageScroll.addView(page);
        root.addView(pageScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- the buttons' legend ----
        root.addView(line());
        TextView legend = text(T("A  Confirmar      B  Voltar      LB / RB  Abas      ◀ ▶  Alterar      START  Jogar"), 12, SUBTLE, false);
        legend.setSingleLine(true);
        legend.setEllipsize(TextUtils.TruncateAt.END);
        legend.setPadding(0, dp(8), 0, 0);
        root.addView(legend);

        setContentView(root);
        root.requestFocus();
        currentTab = Math.max(0, Math.min(TAB_NAMES.length - 1, prefs().getInt("launcher_tab", TAB_PLAY)));
        showTab(currentTab);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putIntArray("tab_selection", tabSelection);
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        starting = false;
        pickerOpen = false;
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        releaseHeld();
        Haptics.cancel();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) releaseHeld();   // a key released under a dialog or another app never reaches this window
    }

    // ---- the controller -------------------------------------------------------------------------------------------------
    private boolean dialogOpen() {
        return openDialog != null && openDialog.isShowing();
    }

    private static int dirOfKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: return Nav.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return Nav.DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return Nav.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return Nav.RIGHT;
            default: return Nav.NONE;
        }
    }

    private void releaseHeld() {
        dpad.release();
        stick.release();
        stickDir = Nav.NONE;
        ui.removeCallbacks(repeatTick);
        repeatPosted = false;
    }

    private void scheduleRepeat() {
        if (repeatPosted || !(dpad.held() || stick.held())) return;
        repeatPosted = true;
        ui.postDelayed(repeatTick, 30);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE)
            return super.dispatchKeyEvent(e);
        int dir = dirOfKey(code);
        if (dir != Nav.NONE) {
            // one step at the press; the repeat is this screen's own (Android's key repeats ignored)
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                int step = dpad.press(dir, SystemClock.uptimeMillis());
                if (step != Nav.NONE) navigate(step);
                scheduleRepeat();
            } else if (e.getAction() == KeyEvent.ACTION_UP && dpad.dir() == dir) {
                dpad.release();
            }
            return true;
        }
        boolean ours;
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_B: case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BUTTON_L1: case KeyEvent.KEYCODE_BUTTON_R1: case KeyEvent.KEYCODE_BUTTON_START:
                ours = true;
                break;
            default:
                ours = false;
        }
        if (!ours) return super.dispatchKeyEvent(e);
        if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() != 0) return true;   // one action a press
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                confirm(e);
                break;
            case KeyEvent.KEYCODE_BUTTON_B: case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_ESCAPE:
                back();
                break;
            case KeyEvent.KEYCODE_BUTTON_L1:
                changeTab(-1, e);
                break;
            case KeyEvent.KEYCODE_BUTTON_R1:
                changeTab(1, e);
                break;
            default:   // START
                if (Nav.startAllowed(currentTab, dialogOpen(), pickerOpen, copying, starting)) {
                    Haptics.tap(this, e, true);
                    startGame(false);
                } else if (currentTab != TAB_PLAY && !dialogOpen()) {
                    tabSelection[TAB_PLAY] = 0;   // JOGAR selected, nothing started: START again plays
                    showTab(TAB_PLAY);
                }
                break;
        }
        return true;
    }

    // The left stick and the d-pad's hat (a controller that sends its d-pad as axes): this screen's own -- returning true
    // keeps Android from making its own d-pad keys out of them (with its own, faster repeat)
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if ((ev.getSource() & (InputDevice.SOURCE_JOYSTICK | InputDevice.SOURCE_GAMEPAD)) == 0 || ev.getAction() != MotionEvent.ACTION_MOVE)
            return super.dispatchGenericMotionEvent(ev);
        long now = SystemClock.uptimeMillis();
        float hx = ev.getAxisValue(MotionEvent.AXIS_HAT_X), hy = ev.getAxisValue(MotionEvent.AXIS_HAT_Y);
        int hat = hy < -0.5f ? Nav.UP : hy > 0.5f ? Nav.DOWN : hx < -0.5f ? Nav.LEFT : hx > 0.5f ? Nav.RIGHT : Nav.NONE;
        if (hat != Nav.NONE) {
            int step = dpad.press(hat, now);
            if (step != Nav.NONE) navigate(step);
        } else if (dpad.held() && ev.getDevice() != null && ev.getDevice().getMotionRange(MotionEvent.AXIS_HAT_X) != null) {
            dpad.release();
        }
        int d = Nav.stickDir(ev.getAxisValue(MotionEvent.AXIS_X), ev.getAxisValue(MotionEvent.AXIS_Y), stickDir);
        if (d != stickDir) {
            stickDir = d;
            if (d == Nav.NONE) stick.release();
            else {
                int step = stick.press(d, now);
                if (step != Nav.NONE) navigate(step);
            }
        }
        scheduleRepeat();
        return true;
    }

    private void navigate(int dir) {
        if (dialogOpen()) return;
        if (dir == Nav.UP || dir == Nav.DOWN) {
            int delta = dir == Nav.UP ? -1 : 1;
            int next = Nav.move(enabledItems(), selected, delta);
            if (next != selected && next >= 0) {
                select(next);
            } else {
                // at the end of the rows: the text below (the notes, the diagnostics) scrolls into view
                pageScroll.scrollBy(0, delta * dp(140));
            }
            return;
        }
        Item it = current();
        if (it != null && it.onStep != null && it.isEnabled()) it.onStep.accept(dir == Nav.LEFT ? -1 : 1);
    }

    private Item current() {
        return selected >= 0 && selected < items.size() ? items.get(selected) : null;
    }

    private void confirm(KeyEvent e) {
        Item it = current();
        if (it == null || !it.isEnabled()) return;
        Haptics.tap(this, e, true);
        act(it);
    }

    private void act(Item it) {
        if (it.onA != null) it.onA.run();
        else if (it.onStep != null) it.onStep.accept(1);
    }

    private void back() {
        if (currentTab != TAB_PLAY) showTab(TAB_PLAY);
        else confirmExit();
    }

    private void changeTab(int delta, KeyEvent e) {
        Haptics.tap(this, e, false);
        showTab(Nav.nextTab(currentTab, delta, TAB_NAMES.length));
    }

    private boolean[] enabledItems() {
        boolean[] en = new boolean[items.size()];
        for (int i = 0; i < en.length; i++) en[i] = items.get(i).isEnabled();
        return en;
    }

    private void select(int index) {
        selected = index;
        if (index >= 0) tabSelection[currentTab] = index;
        paintItems();
        ensureVisible();
    }

    private void paintItems() {
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            boolean on = i == selected;
            boolean en = it.isEnabled();
            if (on) it.view.setBackground(frame());
            else it.view.setBackgroundColor(BG);
            for (TextView t : it.main) t.setTextColor(en ? FG : DIM);
            for (TextView t : it.sub) t.setTextColor(en ? SUBTLE : DIM);
        }
    }

    // the selected row kept on screen with a margin; the first row shows the tab's top (the disc's state on Jogar)
    private void ensureVisible() {
        final Item it = current();
        if (it == null) return;
        pageScroll.post(() -> {
            int first = Nav.firstEnabled(enabledItems(), 0);
            if (selected == first) {
                pageScroll.scrollTo(0, 0);
                return;
            }
            int top = it.view.getTop(), bottom = it.view.getBottom();
            int y = pageScroll.getScrollY(), h = pageScroll.getHeight(), m = dp(56);
            if (top - m < y) pageScroll.scrollTo(0, Math.max(0, top - m));
            else if (bottom + m > y + h) pageScroll.scrollTo(0, bottom + m - h);
        });
    }

    // ---- the tabs -------------------------------------------------------------------------------------------------------
    private void showTab(int index) {
        currentTab = index;
        prefs().edit().putInt("launcher_tab", index).apply();
        for (int i = 0; i < tabLabels.size(); i++) {
            boolean on = i == index;
            tabLabels.get(i).setTextColor(on ? FG : SUBTLE);
            tabLabels.get(i).setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            tabLines.get(i).setBackgroundColor(on ? FG : BG);
        }
        page.removeAllViews();
        items.clear();
        status = null;
        isoName = isoState = lastRun = playLabel = null;
        presetItem = null;
        Settings st = new Settings(this);
        switch (index) {
            case TAB_PLAY:
                playPage();
                break;
            case TAB_GRAPHICS:
                pageTitle("Gráficos", "As mesmas opções ficam no jogo: Opções → Opções gráficas.");
                section("IMAGEM");
                choiceRows(st, GRAPHICS, Settings.SCALE, Settings.ASPECT, Settings.FRAME_RATE, Settings.VSYNC, Settings.FPS_COUNTER);
                section("QUALIDADE");
                presetItem = presetRow(st);
                choiceRows(st, GRAPHICS, Settings.TEXTURE_FILTER, Settings.ANISOTROPY, Settings.FXAA, Settings.DITHERING, Settings.DRAW_DISTANCE);
                break;
            case TAB_AUDIO:
                pageTitle("Áudio", T("O volume dos efeitos e da música fica no menu do jogo (Opções → Opções de som). ")
                        + T("Driver e buffer também estão no jogo: Opções → android settings."));
                section("SAÍDA DE ÁUDIO");
                for (Choice c : AUDIO) choiceRow(st, c);
                break;
            case TAB_CONTROLS:
                pageTitle("Controles", "Os analógicos mudam na hora, também pelo jogo (Opções → android settings).");
                section("ANALÓGICOS");
                choiceRows(st, CONTROLS, Settings.STICK_DEADZONE, Settings.STICK_SENSITIVITY);
                section("VIBRAÇÃO");
                choiceRows(st, CONTROLS, "android.rumble", Haptics.KEY);
                section("BOTÕES");
                note("Escolha um botão do PS2 e aperte o botão do controle que deve fazer o papel dele. Se o botão "
                        + "já estiver em uso, os dois trocam de lugar. Vale no próximo início do jogo; o launcher não muda.");
                for (int i = 0; i < PAD_BUTTONS.length; i++) padRow(st, i);
                actionRow("Restaurar botões padrão", "O layout do controle volta ao do DualShock 2", this::confirmPadRestore);
                break;
            case TAB_SAVES:
                savesPage();
                break;
            default:
                pageTitle("Sistema", "Idioma, restauração, diagnóstico e informações do aplicativo.");
                systemPage(st);
                break;
        }
        selected = Nav.firstEnabled(enabledItems(), tabSelection[index]);
        if (selected >= 0) tabSelection[index] = selected;
        paintItems();
        pageScroll.scrollTo(0, 0);
        ensureVisible();
    }

    /** The tab built again where it is (a change that touches other rows); the selected row stays the same. */
    private void rebuild() {
        int keep = pageScroll.getScrollY();
        showTab(currentTab);
        pageScroll.post(() -> {
            pageScroll.scrollTo(0, keep);
            ensureVisible();
        });
    }

    private void playPage() {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(4), dp(10), dp(4), dp(14));
        head.addView(text("Crash Twinsanity", 30, FG, true));
        head.addView(text("TwinsanityEvolved · port nativo Android", 13, SUBTLE, false));
        TextView label = text("JOGO SELECIONADO", 11, SUBTLE, true);
        label.setPadding(0, dp(18), 0, dp(2));
        head.addView(label);
        isoName = text("—", 17, FG, true);
        isoState = text("", 13, SUBTLE, false);
        head.addView(isoName);
        head.addView(isoState);
        page.addView(head);
        page.addView(line());
        Item play = row("JOGAR", null, "START", true);
        playLabel = play.main.get(0);
        play.onA = () -> startGame(false);
        play.enabled = () -> !starting && !copying;
        Item pick = row("Selecionar ISO ou CHD…", "Copia a SUA imagem do jogo para o app (nenhuma permissão de armazenamento)", "›", false);
        pick.onA = this::pickIso;
        pick.enabled = () -> !copying;
        lastRun = text("", 12, SUBTLE, false);
        lastRun.setPadding(dp(4), dp(14), dp(4), 0);
        page.addView(lastRun);
        note("No jogo: Opções → android settings → return to launcher volta para cá.");
        updatePlayState();
    }

    private void systemPage(Settings st) {
        section("IDIOMA");
        choiceRow(st, LANGUAGE);
        note("O launcher muda na hora; as opções do port dentro do jogo ficam em inglês (a fonte do jogo).");
        section("RESTAURAR");
        actionRow("Restaurar apenas gráficos",
            "Para tela preta ou falha ao iniciar: só as opções gráficas voltam ao padrão (áudio, controles, idioma e saves ficam)",
            this::confirmRestoreGraphics);
        actionRow("Restaurar configurações padrão", "Gráficos, áudio e controles voltam aos valores recomendados", this::confirmRestore);
        section("DIAGNÓSTICO");
        actionRow("Compartilhar relatório", "Um arquivo de texto com os logs da última execução", () -> buildReport(true));
        actionRow("Copiar relatório", "O mesmo relatório para a área de transferência", () -> buildReport(false));
        final boolean diag = prefs().getBoolean("diagnostic_mode", false);
        Item mode = row("Modo diagnóstico", "Registra as mensagens da GPU, os analógicos e a vibração do jogo e permite capturar um quadro com L3 + R3 (deixe desligado para jogar)",
                T(diag ? "Ligado" : "Desligado"), false);
        mode.onA = () -> {
            prefs().edit().putBoolean("diagnostic_mode", !diag).apply();
            Diag.log(this, "main", "diagnostic mode " + (!diag ? "on" : "off"));
            rebuild();
        };
        actionRow("Diagnóstico sem o jogo", "Carrega as bibliotecas nativas e testa o ambiente", () -> startActivity(new Intent(this, ProbeActivity.class)));
        actionRow("Teste do SDL sem o jogo", "Abre a janela, o controle e o áudio sem a ISO", this::startSdlTest);
        section("APLICATIVO");
        actionRow("Sair do aplicativo", "Fecha o TwinsanityEvolved (configurações, saves e a ISO ficam guardados)", this::confirmExit);
        note(T("Arquivo de configurações (compartilhado com o jogo):\n") + st.file().getAbsolutePath());
        note(T("Versão ") + versionName() + " · " + Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ") · " + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?"));
        status = text("", 11, SUBTLE, false);
        status.setTypeface(Typeface.MONOSPACE);
        status.setPadding(dp(4), dp(14), dp(4), dp(6));
        page.addView(status);
        status.setText(statusText());
    }

    // A settings tab's name and what it holds, above its rows
    private void pageTitle(String name, String about) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(4), dp(10), dp(4), dp(2));
        head.addView(text(name, 28, FG, true));
        TextView a = text(about, 13, SUBTLE, false);
        a.setPadding(0, dp(4), 0, 0);
        head.addView(a);
        page.addView(head);
    }

    /** The rows of `group` with these keys, in this order */
    private void choiceRows(Settings st, Choice[] group, String... keys) {
        for (String k : keys)
            for (Choice c : group)
                if (c.key.equals(k)) choiceRow(st, c);
    }

    // ---- the saves (SaveManager.java) ------------------------------------------------------------------------------------
    private File gameDir() {
        return new File(getFilesDir(), "game");
    }

    private File backupDir() {
        return new File(getFilesDir(), "save-backups");
    }

    private boolean gameRunning() {
        if (!Diag.processAlive(this, "game")) return false;
        dialog(T("O jogo está aberto"), T("Saia do jogo (Opções → android settings → return to launcher) antes de mexer nos saves: o jogo grava no cartão de memória."),
            null, new String[] {T("OK")}, 0, 0, null);
        return true;
    }

    private void savesPage() {
        pageTitle("Saves", "Os saves do cartão de memória do jogo: backup, restauração e exclusão.");
        section("BACKUP");
        actionRow("Exportar backup", "Salva todos os cartões de memória num arquivo .zip onde você escolher", () -> {
            if (gameRunning()) return;
            String name = "crash-twinsanity-saves-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new java.util.Date()) + ".zip";
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
                .putExtra(Intent.EXTRA_TITLE, name);
            launchPicker(i, REQUEST_EXPORT_SAVES);
        });
        actionRow("Importar backup", "Restaura um .zip exportado (os cartões atuais ganham um backup automático antes)", () -> {
            if (gameRunning()) return;
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/zip", "application/x-zip-compressed", "application/octet-stream"});
            launchPicker(i, REQUEST_IMPORT_SAVES);
        });
        section("SAVES NO CARTÃO");
        List<SaveManager.Save> saves = SaveManager.list(gameDir());
        if (saves.isEmpty()) note("Nenhum save ainda. Os saves aparecem aqui depois de salvar dentro do jogo.");
        java.text.DateFormat when = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT);
        for (final SaveManager.Save sv : saves) {
            String card = sv.card.equals("memcards") ? T("cartão principal") : sv.card.equals("memcards-mods") ? T("cartão dos mods") : sv.card;
            Item it = row(sv.title, card + " · " + sv.port + " · " + SaveManager.humanSize(sv.bytes) + " · " + when.format(new java.util.Date(sv.modified)), "›", false);
            it.onA = () -> confirmDeleteSave(sv);
        }
        note(T("Backups automáticos (antes de importar ou apagar): ") + backupDir().getAbsolutePath());
    }

    private void launchPicker(Intent i, int request) {
        try {
            pickerOpen = true;
            startActivityForResult(i, request);
        } catch (Exception e) {
            pickerOpen = false;
            Toast.makeText(this, T("Nenhum gerenciador de arquivos disponível."), Toast.LENGTH_LONG).show();
        }
    }

    /** A copy of every card in files/save-backups before an import or a deletion; null when it failed */
    private File autoBackup(String why) {
        File dir = backupDir();
        dir.mkdirs();
        File f = new File(dir, why + "-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new java.util.Date()) + ".zip");
        try (FileOutputStream out = new FileOutputStream(f)) {
            SaveManager.exportZip(gameDir(), out);
            Diag.log(this, "main", "saves: automatic backup " + f.getName());
            return f;
        } catch (Exception e) {
            Diag.log(this, "main", "saves: automatic backup failed: " + e);
            f.delete();
            return null;
        }
    }

    private void confirmDeleteSave(final SaveManager.Save sv) {
        if (gameRunning()) return;
        dialog(T("Apagar save?"), "\"" + sv.title + "\" (" + sv.port + ", " + SaveManager.humanSize(sv.bytes) + ")\n\n"
                + T("Um backup automático de todos os cartões é feito antes, em save-backups."),
            null, new String[] {T("Apagar"), T("Cancelar")}, 1, 1, w -> {
                if (w != 0) return;
                if (autoBackup("antes-de-apagar") == null) {
                    Toast.makeText(this, T("O backup automático falhou: nada foi apagado."), Toast.LENGTH_LONG).show();
                    return;
                }
                boolean ok = SaveManager.deleteTree(sv.dir);
                Diag.log(this, "main", "saves: deleted " + sv.dir + (ok ? "" : " (NOT all)"));
                Toast.makeText(this, ok ? T("Save apagado") : T("Não foi possível apagar tudo"), Toast.LENGTH_LONG).show();
                rebuild();
            });
    }

    private void exportSaves(final Uri uri) {
        new Thread(() -> {
            String msg;
            try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new java.io.IOException("destino indisponível");
                int n = SaveManager.exportZip(gameDir(), out);
                msg = T("Backup exportado: ") + n + T(" arquivo(s)");
                Diag.log(this, "main", "saves: exported " + n + " file(s)");
            } catch (Exception e) {
                msg = T("A exportação falhou: ") + e.getMessage();
                Diag.log(this, "main", "saves: export failed: " + e);
            }
            final String m = msg;
            runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
        }, "export-saves").start();
    }

    private void importSaves(final Uri uri) {
        dialog(T("Importar backup?"), T("Os cartões de memória que estão no arquivo substituem os atuais. Um backup automático dos atuais é feito antes, em save-backups."),
            null, new String[] {T("Importar"), T("Cancelar")}, 1, 1, w -> {
                if (w != 0) return;
                new Thread(() -> {
                    String msg;
                    File tmp = new File(getCacheDir(), "import-saves.zip");
                    try {
                        try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(tmp)) {
                            if (in == null) throw new java.io.IOException("arquivo indisponível");
                            SaveManager.copy(in, out);
                        }
                        if (!SaveManager.cardFolders(gameDir()).isEmpty() && autoBackup("antes-de-importar") == null)
                            throw new java.io.IOException("o backup automático falhou: nada foi importado");
                        int n = SaveManager.importZip(tmp, gameDir());
                        msg = T("Backup importado: ") + n + T(" arquivo(s)");
                        Diag.log(this, "main", "saves: imported " + n + " file(s)");
                    } catch (Exception e) {
                        msg = T("A importação falhou: ") + e.getMessage();
                        Diag.log(this, "main", "saves: import failed: " + e);
                    } finally {
                        tmp.delete();
                    }
                    final String m = msg;
                    runOnUiThread(() -> {
                        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                        if (currentTab == TAB_SAVES) rebuild();
                    });
                }, "import-saves").start();
            });
    }

    private void note(String s) {
        TextView t = text(s, 12, SUBTLE, false);
        t.setPadding(dp(4), dp(10), dp(4), dp(4));
        page.addView(t);
    }

    private void section(String s) {
        TextView t = text(s, 11, SUBTLE, true);
        t.setPadding(dp(4), dp(22), dp(4), dp(6));
        page.addView(t);
        page.addView(line());
    }

    // A row: the label (and its help) on the left, the value on the right, a separator under it. A tap selects it and acts.
    private Item row(String label, String help, String value, boolean large) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(dp(14), dp(large ? 16 : 11), dp(14), dp(large ? 16 : 11));
        v.setMinimumHeight(dp(large ? 64 : 52));
        final Item it = new Item(v);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView l = text(label, large ? 22 : 16, FG, large);
        words.addView(l);
        it.main.add(l);
        if (help != null) {
            TextView h = text(help, 12, SUBTLE, false);
            h.setPadding(0, dp(2), 0, 0);
            words.addView(h);
            it.sub.add(h);
        }
        v.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (value != null) {
            TextView val = text(value, large ? 15 : 15, FG, false);
            val.setSingleLine(true);
            val.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            val.setPadding(dp(16), 0, 0, 0);
            v.addView(val, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            it.main.add(val);
        }
        v.setClickable(true);
        v.setOnClickListener(x -> {
            if (dialogOpen()) return;
            int index = items.indexOf(it);
            if (index < 0 || !it.isEnabled()) return;
            select(index);
            Haptics.tap(this, null, true);
            act(it);
        });
        page.addView(v);
        page.addView(line());
        items.add(it);
        return it;
    }

    private Item actionRow(String label, String help, Runnable action) {
        Item it = row(label, help, "›", false);
        it.onA = action;
        return it;
    }

    private static String chooser(String name) {
        return "‹   " + name + "   ›";
    }

    // A setting: left / right = the previous / next value, A or a tap = the next; written at once, shown at once
    private Item choiceRow(final Settings st, final Choice c) {
        final int[] at = {c.index(st.get(c.key, c.def))};
        final Item it = row(c.label, c.help, chooser(T(c.names[at[0]])), false);
        final TextView value = it.main.get(it.main.size() - 1);
        it.onStep = d -> {
            at[0] = (at[0] + d + c.values.length) % c.values.length;
            if (st.set(c.key, c.values[at[0]])) {
                Diag.log(this, "main", "setting " + c.key + " = " + c.values[at[0]]);
            } else {
                Toast.makeText(this, T("Não foi possível salvar em ") + st.file(), Toast.LENGTH_LONG).show();
            }
            value.setText(chooser(T(c.names[at[0]])));
            if (Haptics.KEY.equals(c.key)) Haptics.refresh(this);
            if (Lang.KEY.equals(c.key)) {   // the launcher's language: the screen is built again in it
                Lang.init(this);
                recreate();
                return;
            }
            if (presetItem != null && presetItem != it) {
                // the preset row names what the rows now make (Personalizado once they differ from all three)
                presetItem.main.get(presetItem.main.size() - 1).setText(chooser(T(PRESET_NAMES[presetNow(st)])));
            }
        };
        return it;
    }

    // Leaving the app (not the same as the game's "return to launcher", which ends a game session and comes back here): the
    // launcher's task is finished and removed from the recent apps. A game or diagnostic process still running (normally
    // none while this screen is shown: the game's process ends when its activity finishes) is ended first, so nothing of
    // the app keeps running. The settings, the saves, the ISO and the logs are files: nothing is lost.
    private void confirmExit() {
        final boolean gameAlive = Diag.processAlive(this, "game");
        dialog(T("Sair do aplicativo?"),
            T(gameAlive
                ? "O jogo ainda está aberto em segundo plano. Saia pelo jogo (Opções → android settings → return to launcher) "
                  + "para não perder progresso não salvo. Sair mesmo assim encerra o jogo."
                : "O TwinsanityEvolved será fechado. Configurações, saves e a ISO continuam guardados."),
            null, new String[] {T("Sair"), T("Cancelar")}, 1, 1, i -> { if (i == 0) exitApp(); });
    }

    /** A dialog over the launcher (OledDialog): a held direction is let go, and START is refused while it is shown. */
    private OledDialog dialog(String title, String text, View extra, String[] labels, int defaultIndex, int cancelIndex,
                              OledDialog.OnChoice onChoice) {
        releaseHeld();
        final OledDialog d = OledDialog.show(this, title, text, extra, labels, defaultIndex, cancelIndex, onChoice);
        openDialog = d;
        d.setOnDismissListener(x -> { if (openDialog == d) openDialog = null; });
        return d;
    }

    private void exitApp() {
        Diag.log(this, "main", "exit requested from the launcher");
        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null && am.getRunningAppProcesses() != null) {
            for (android.app.ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses()) {
                if (p.processName != null && (p.processName.endsWith(":game") || p.processName.endsWith(":probe"))) {
                    Diag.log(this, "main", "ending " + p.processName + " (pid " + p.pid + ") before leaving");
                    android.os.Process.killProcess(p.pid);
                }
            }
        }
        finishAndRemoveTask();
    }


    // ---- the gamepad-button remap (ps2r.ini android.pad.<ps2 button> = <SDL control>; the game applies it at its start) ----
    // The PS2 buttons (the engine's [pad] names), their labels, and SDL's standard layout (the DualShock 2's)
    static final String[] PAD_BUTTONS = {"cross", "circle", "square", "triangle", "l1", "r1", "l2", "r2", "l3", "r3",
                                         "select", "start", "up", "down", "left", "right"};
    static final String[] PAD_LABELS = {"Xis (✕)", "Círculo (○)", "Quadrado (□)", "Triângulo (△)", "L1", "R1", "L2", "R2",
                                        "L3 (analógico esquerdo)", "R3 (analógico direito)", "Select", "Start",
                                        "Direcional ↑", "Direcional ↓", "Direcional ←", "Direcional →"};
    static final String[] PAD_DEFAULTS = {"a", "b", "x", "y", "leftshoulder", "rightshoulder", "lefttrigger", "righttrigger",
                                          "leftstick", "rightstick", "back", "start", "dpup", "dpdown", "dpleft", "dpright"};

    // An Android gamepad key as SDL's game-controller control name (SDL's Android joystick maps the same keys)
    static String sdlControl(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return "a";
            case KeyEvent.KEYCODE_BUTTON_B: return "b";
            case KeyEvent.KEYCODE_BUTTON_X: return "x";
            case KeyEvent.KEYCODE_BUTTON_Y: return "y";
            case KeyEvent.KEYCODE_BUTTON_L1: return "leftshoulder";
            case KeyEvent.KEYCODE_BUTTON_R1: return "rightshoulder";
            case KeyEvent.KEYCODE_BUTTON_L2: return "lefttrigger";
            case KeyEvent.KEYCODE_BUTTON_R2: return "righttrigger";
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return "leftstick";
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return "rightstick";
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "back";
            case KeyEvent.KEYCODE_BUTTON_START: return "start";
            case KeyEvent.KEYCODE_DPAD_UP: return "dpup";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "dpdown";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "dpleft";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "dpright";
            default: return null;
        }
    }

    static String controlLabel(String c) {
        switch (c) {
            case "a": return "A";
            case "b": return "B";
            case "x": return "X";
            case "y": return "Y";
            case "leftshoulder": return "L1";
            case "rightshoulder": return "R1";
            case "lefttrigger": return "L2";
            case "righttrigger": return "R2";
            case "leftstick": return "L3";
            case "rightstick": return "R3";
            case "back": return "Select";
            case "start": return "Start";
            case "dpup": return "↑";
            case "dpdown": return "↓";
            case "dpleft": return "←";
            case "dpright": return "→";
            default: return c;
        }
    }

    private String padControl(Settings st, int i) {
        return st.get("android.pad." + PAD_BUTTONS[i], PAD_DEFAULTS[i]);
    }


    private Item padRow(final Settings st, final int i) {
        Item it = row(PAD_LABELS[i], null, controlLabel(padControl(st, i)), false);   // (A, B, L1, ...: the same in both languages)
        it.onA = () -> capturePad(st, i);
        return it;
    }

    // Press-to-bind: the next gamepad key is the PS2 button's control; a control already bound to another PS2 button
    // swaps with this one's (no button is ever left without a control). B binds like any other button: the dialog is
    // cancelled by its button (touch), Android's back gesture or 6 s.
    private void capturePad(final Settings st, final int i) {
        final OledDialog[] holder = new OledDialog[1];
        final Runnable timeout = () -> { if (holder[0] != null) holder[0].close(); };
        holder[0] = dialog(T(PAD_LABELS[i]),
            T("Aperte agora o botão do controle para ") + T(PAD_LABELS[i]) + T(".\n(Atual: ") + controlLabel(padControl(st, i))
                + T(". Cancela sozinho em 6 segundos.)"),
            null, new String[] {T("Cancelar")}, 0, 0, x -> ui.removeCallbacks(timeout));
        holder[0].setKeyInterceptor(e -> {
            String control = sdlControl(e.getKeyCode());
            if (control == null) return false;
            if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() != 0) return true;
            String old = padControl(st, i);
            boolean ok = true;
            for (int k = 0; k < PAD_BUTTONS.length; k++)
                if (k != i && padControl(st, k).equals(control)) ok &= st.set("android.pad." + PAD_BUTTONS[k], old);
            ok &= st.set("android.pad." + PAD_BUTTONS[i], control);
            Diag.log(this, "main", "pad remap: " + PAD_BUTTONS[i] + " = " + control + (ok ? "" : " (NOT written)"));
            ui.removeCallbacks(timeout);
            Haptics.tap(this, e, true);
            holder[0].close();
            rebuild();
            return true;
        });
        ui.postDelayed(timeout, 6000);
    }

    private void confirmPadRestore() {
        dialog(T("Restaurar botões padrão?"), T("Todos os botões voltam ao layout do DualShock 2 (A = ✕, B = ○, X = □, Y = △...)."),
            null, new String[] {T("Restaurar"), T("Cancelar")}, 1, 1, w -> {
                if (w != 0) return;
                Settings st = new Settings(this);
                for (int k = 0; k < PAD_BUTTONS.length; k++) st.set("android.pad." + PAD_BUTTONS[k], PAD_DEFAULTS[k]);
                Diag.log(this, "main", "pad remap restored to the standard layout");
                rebuild();
            });
    }

    // ---- graphics presets: the filter, the anisotropy, the LOD, FXAA and dithering together (the game's 'graphics preset'
    // row is the same, display.cpp); the resolution, the frame rate, the shape, the audio and the controls never change ----
    static final String[] PRESET_NAMES = {"Original", "Equilibrado", "Qualidade", "Personalizado"};
    static final String[][] PRESETS = {   // anisotropy, LOD
        {"1", "original"}, {"4", "medium"}, {"16", "max"}};

    private int presetNow(Settings st) {
        for (int i = 0; i < PRESETS.length; i++) {
            if (st.get(Settings.TEXTURE_FILTER, "bilinear").equals("bilinear") && st.get(Settings.ANISOTROPY, "1").equals(PRESETS[i][0])
                    && st.get(Settings.DRAW_DISTANCE, "original").equals(PRESETS[i][1]) && st.get(Settings.FXAA, "off").equals("off")
                    && st.get(Settings.DITHERING, "off").equals("off"))
                return i;
        }
        return 3;
    }


    private Item presetRow(final Settings st) {
        Item it = row("Predefinição gráfica",
            "Original: como o jogo. Equilibrado: anisotrópica 4x, LOD 2x. Qualidade: anisotrópica 16x, LOD 4x. A resolução não muda (2x ainda não validado)",
            chooser(T(PRESET_NAMES[presetNow(st)])), false);
        it.onStep = d -> {
            int now = presetNow(st);
            int next = now == 3 ? (d > 0 ? 0 : 2) : (now + d + 3) % 3;
            st.set(Settings.TEXTURE_FILTER, "bilinear");
            st.set(Settings.ANISOTROPY, PRESETS[next][0]);
            st.set(Settings.DRAW_DISTANCE, PRESETS[next][1]);
            st.set(Settings.FXAA, "off");
            st.set(Settings.DITHERING, "off");
            Diag.log(this, "main", "graphics preset " + PRESET_NAMES[next]);
            rebuild();
        };
        return it;
    }

    // Recovery: the graphic options alone back to the Android profile (a black screen or a failed start after a graphics
    // change); the audio, the controls, the language, the ISO and the saves are not touched
    private void confirmRestoreGraphics() {
        dialog(T("Restaurar apenas gráficos?"), T("Filtro, resolução, proporção, FPS, VSync, anisotrópica, FXAA, dithering, LOD e contador voltam ao padrão."),
            null, new String[] {T("Restaurar"), T("Cancelar")}, 1, 1, w -> {
                if (w != 0) return;
                Settings st = new Settings(this);
                boolean ok = true;
                for (Choice c : GRAPHICS) ok &= st.set(c.key, c.def);
                ok &= st.set(Settings.FULLSCREEN, "fullscreen");
                Diag.log(this, "main", "graphics restored to the defaults (" + (ok ? "written" : "NOT all written") + ")");
                Toast.makeText(this, ok ? T("Gráficos restaurados") : T("Falha ao salvar em ") + st.file(), Toast.LENGTH_LONG).show();
                rebuild();
            });
    }

    private void confirmRestore() {
        dialog(T("Restaurar configurações padrão?"),
            T("Widescreen 16:9, VSync ligado, filtro bilinear, anisotrópica, FXAA e dithering desligados, resolução 1x, 60 FPS, contador desligado, LOD original, OpenSL ES com ")
                + T("buffer 2048, analógicos no padrão. A ISO e os saves não mudam; a opção widescreen do jogo fica no save (Opções → Opções gráficas)."),
            null, new String[] {T("Restaurar"), T("Cancelar")}, 1, 1, w -> {
                if (w != 0) return;
                Settings st = new Settings(this);
                boolean ok = true;
                for (Choice[] group : new Choice[][] {GRAPHICS, AUDIO, CONTROLS})
                    for (Choice c : group) ok &= st.set(c.key, c.def);
                Haptics.refresh(this);
                Diag.log(this, "main", "settings restored to the defaults (" + (ok ? "written" : "NOT all written") + ")");
                Toast.makeText(this, ok ? T("Configurações padrão restauradas") : T("Falha ao salvar em ") + st.file(), Toast.LENGTH_LONG).show();
                rebuild();
            });
    }

    // ---- the game's state on the Jogar tab -------------------------------------------------------------------------------
    private void refresh() {
        updatePlayState();
        if (status != null) status.setText(statusText());
        paintItems();
    }

    private void updatePlayState() {
        if (playLabel != null) playLabel.setText(T(starting ? "Iniciando…" : "JOGAR"));
        if (isoName == null) return;
        String source = prefs().getString("iso_source", null);
        String[] disc = Diag.findDiscImage(this);
        if (disc[0] == null) {
            isoName.setText(T("Nenhuma ISO selecionada"));
            isoState.setText(T("Escolha \"Selecionar ISO ou CHD…\" e indique a SUA imagem do Crash Twinsanity (PAL ou NTSC-U)."));
            isoState.setTextColor(FG);
        } else {
            StringBuilder r = new StringBuilder();
            String err = Diag.checkDiscImage(disc[0], r);
            isoName.setText(source != null ? source : new File(disc[0]).getName());
            isoState.setText(err == null ? "✓  " + T("Pronta — ") + r : "✕  " + T("Não utilizável: ") + err);
            isoState.setTextColor(err == null ? SUBTLE : FG);
        }
        if (lastRun != null) {
            String[] line = lastRunLine();
            lastRun.setText(T(line[0]));
            lastRun.setTextColor(line[1] != null ? FG : SUBTLE);
        }
    }

    // what the last start did, in one line (Sistema's diagnostics have the detail); [1] non-null when it went wrong
    private String[] lastRunLine() {
        File f = new File(Diag.logsDir(this), "stage-game.txt");
        String s = Diag.readFile(f, 2000);
        if (s == null) return new String[] {"Ainda não executado nesta instalação.", null};
        s = s.trim();
        if (s.startsWith("running")) {
            if (Diag.processAlive(this, "game")) return new String[] {"O jogo está em execução.", null};
            return new String[] {"A última execução terminou de forma inesperada. Veja Sistema → Compartilhar relatório.", "bad"};
        }
        if (s.startsWith("crash")) return new String[] {"A última execução travou. Veja Sistema → Compartilhar relatório.", "bad"};
        return new String[] {"A última execução terminou normalmente.", null};
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("twinsanity", MODE_PRIVATE);
    }

    private void startGame(boolean force) {
        if (copying) {
            Toast.makeText(this, T("Aguarde a cópia da ISO terminar."), Toast.LENGTH_SHORT).show();
            return;
        }
        if (starting) return;
        String[] disc = Diag.findDiscImage(this);
        StringBuilder r = new StringBuilder();
        String err = disc[0] == null ? disc[1] : Diag.checkDiscImage(disc[0], r);
        if (err != null && !force) {
            dialog(T("Imagem do disco não disponível"),
                T("O jogo precisa da SUA imagem do Crash Twinsanity (PAL SLES-52568 ou NTSC-U SLUS-20909 v2.00).\n\n")
                    + T("Escolha \"Selecionar ISO ou CHD…\" e indique o arquivo no gerenciador de arquivos do Android.\n\n")
                    + T("Detalhe: ") + err,
                null, new String[] {T("Selecionar ISO"), T("Iniciar mesmo assim"), T("Cancelar")}, 0, 2, w -> {
                    if (w == 0) pickIso();
                    else if (w == 1) startGame(true);
                });
            return;
        }
        starting = true;
        updatePlayState();
        paintItems();
        Diag.log(this, "main", "starting the game (disc: " + disc[0] + ", " + r + ")");
        Intent i = new Intent(this, MainActivity.class);
        if (err == null) i.putExtra(MainActivity.EXTRA_ISO, disc[0]);
        startActivity(i);
    }

    private void startSdlTest() {
        Diag.log(this, "main", "starting the SDL test (no game)");
        Intent i = new Intent(this, MainActivity.class);
        i.putExtra(MainActivity.EXTRA_SDL_TEST, true);
        startActivity(i);
    }

    private String describeStage(String proc, String label) {
        File f = new File(Diag.logsDir(this), "stage-" + proc + ".txt");
        String s = Diag.readFile(f, 2000);
        if (s == null) return label + ": nunca executado nesta instalação\n";
        s = s.trim();
        StringBuilder b = new StringBuilder(label).append(" (")
            .append(new java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.US).format(new java.util.Date(f.lastModified())))
            .append("): ");
        boolean alive = Diag.processAlive(this, proc);
        if (s.startsWith("running")) {
            if (alive) b.append("em execução agora — ").append(s.substring(8));
            else b.append("TERMINOU DE FORMA INESPERADA durante a etapa ").append(s.substring(8))
                  .append("\n    (nenhum sinal de falha foi registrado: o processo pode ter sido encerrado pelo Android, por exemplo por falta de memória, ou por uma exceção Java — veja o relatório)");
        } else if (s.startsWith("crash")) {
            b.append("TRAVOU — ").append(s);
        } else if (s.startsWith("exited")) {
            b.append("encerrado por exit() — ").append(s.substring(7));
        } else if (s.startsWith("finished")) {
            b.append("terminou normalmente — ").append(s.substring(9));
        } else {
            b.append(s);
        }
        b.append('\n');
        String jc = Diag.readFile(new File(Diag.logsDir(this), "java-crash-" + proc + ".txt"), 1500);
        if (jc != null) b.append("    exceção Java registrada:\n").append(jc).append('\n');
        return b.toString();
    }

    private String statusText() {
        StringBuilder b = new StringBuilder();
        String version = "?";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        b.append("versão ").append(version).append(" — ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
         .append(", Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append("), ABI ")
         .append(Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?").append("\n\n");

        File ext = getExternalFilesDir(null);
        b.append("IMAGEM DO DISCO\n");
        String source = prefs().getString("iso_source", null);
        if (source != null) b.append("  selecionada: ").append(source).append('\n');
        String[] disc = Diag.findDiscImage(this);
        if (disc[0] == null) {
            b.append("  NENHUMA imagem encontrada — ").append(disc[1]).append('\n')
             .append("  Toque em \"Selecionar ISO\" e escolha a SUA imagem do jogo no gerenciador de arquivos.\n")
             .append("  (pasta alternativa: ").append(ext != null ? ext.getAbsolutePath() : "indisponível").append("/twinsanity.iso)\n");
        } else {
            StringBuilder r = new StringBuilder();
            String err = Diag.checkDiscImage(disc[0], r);
            b.append("  ").append(disc[0]).append(" (").append(disc[1]).append(")\n  ").append(r)
             .append(err == null ? " — OK" : " — NÃO UTILIZÁVEL: " + err).append('\n');
            if (err != null && err.contains("EACCES"))
                b.append("  O arquivo existe, mas o Android não deixa o app lê-lo (EACCES). Use \"Selecionar ISO\": o app copia a\n")
                 .append("  imagem pelo seletor do Android para o seu armazenamento privado, sem pedir permissões de armazenamento.\n");
        }
        Settings st = new Settings(this);
        b.append("\nÁUDIO: buffer ").append(st.get(Settings.AUDIO_BUFFER, "2048")).append(" frames, driver ")
         .append(st.get(Settings.AUDIO_DRIVER, DEFAULT_AUDIO_DRIVER)).append(" (").append(st.file()).append(")\n");
        b.append("MODO DIAGNÓSTICO: ").append(prefs().getBoolean("diagnostic_mode", false) ? "ligado" : "desligado").append('\n');
        b.append('\n').append("ÚLTIMAS EXECUÇÕES\n");
        b.append(describeStage("game", "  jogo / teste SDL"));
        b.append(describeStage("probe", "  diagnóstico"));

        String probe = Diag.readFile(new File(Diag.logsDir(this), "probe-result.txt"), 6000);
        if (probe != null && !probe.isEmpty()) b.append("\nÚLTIMO DIAGNÓSTICO\n").append(probe).append('\n');

        String java = Diag.tailLines(new File(Diag.logsDir(this), "java-game.log"), 12);
        if (java != null) b.append("\nLOG JAVA DO JOGO (fim)\n").append(java);
        String nat = Diag.tailLines(new File(Diag.logsDir(this), "latest-game.log"), 40);
        if (nat != null) b.append("\nLOG NATIVO DO JOGO (fim)\n").append(nat);
        String fullNat = Diag.readFile(new File(Diag.logsDir(this), "latest-game.log"), 200000);
        if (fullNat != null && fullNat.contains("FATAL")) {
            String frames = Diag.symbolizeFrames(this, fullNat);
            if (!frames.isEmpty()) b.append("\nFUNÇÕES NO TRAVAMENTO (libmain.so)\n").append(frames);
        }
        b.append("\nLogs: ").append(Diag.logsDir(this).getAbsolutePath()).append('\n');
        return b.toString();
    }


    // --- the disc image picker (Storage Access Framework) and the copy into the app's private storage ---------------------
    // The engine reads a file path; a document URI is not one, and a file another app put in Android/data may not be
    // readable by this app (EACCES on Android 11+). So the chosen document is copied through the ContentResolver into
    // files/disc/twinsanity.iso (private, readable by every process of the app), checked, and then used. No storage
    // permission is asked for: the picker grants this one document, and the grant is kept (takePersistableUriPermission).
    private void pickIso() {
        if (copying) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            pickerOpen = true;
            startActivityForResult(i, REQUEST_PICK_ISO);
        } catch (Exception e) {
            pickerOpen = false;
            Diag.log(this, "main", "ACTION_OPEN_DOCUMENT failed: " + e);
            Toast.makeText(this, T("O seletor de arquivos do Android não abriu: ") + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_EXPORT_SAVES || requestCode == REQUEST_IMPORT_SAVES) {
            pickerOpen = false;
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            if (requestCode == REQUEST_EXPORT_SAVES) exportSaves(data.getData());
            else importSaves(data.getData());
            return;
        }
        if (requestCode != REQUEST_PICK_ISO) return;
        pickerOpen = false;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            Diag.log(this, "main", "ISO picker: cancelled");
            return;
        }
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Diag.log(this, "main", "ISO picker: persistable read permission kept for " + uri);
        } catch (SecurityException e) {
            Diag.log(this, "main", "ISO picker: no persistable permission for " + uri + " (" + e.getMessage() + ")");
        }
        prefs().edit().putString("iso_uri", uri.toString()).apply();
        copyIso(uri);
    }

    private static String mb(long bytes) {
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }


    private void copyIso(final Uri uri) {
        String name = "(sem nome)";
        long size = -1;
        try (Cursor c = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                if (!c.isNull(0)) name = c.getString(0);
                if (!c.isNull(1)) size = c.getLong(1);
            }
        } catch (Exception e) {
            Diag.log(this, "main", "ISO picker: query failed: " + e);
        }
        Diag.log(this, "main", "ISO picker: chose " + name + " (" + (size >= 0 ? mb(size) : "size unknown") + ") " + uri);
        final File target = Diag.importedDisc(this);
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory()) dir.mkdirs();
        long free = new StatFs(getFilesDir().getAbsolutePath()).getAvailableBytes();
        long existing = target.isFile() ? target.length() : 0;
        if (size > 0 && size + 64L * 1048576 > free + existing) {
            dialog(T("Espaço insuficiente"),
                T("A ISO tem ") + mb(size) + T(" e o armazenamento interno tem ") + mb(free + existing) + T(" livres. Libere espaço e tente de novo."),
                null, new String[] {T("OK")}, 0, 0, null);
            Diag.log(this, "main", "ISO copy refused: " + mb(size) + " needed, " + mb(free + existing) + " free");
            return;
        }

        final String displayName = name;
        final long total = size;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final TextView label = text("", 14, FG, false);
        label.setText(T("Copiando ") + displayName + "…");
        final ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setIndeterminate(total <= 0);
        bar.setProgressTintList(ColorStateList.valueOf(FG));
        bar.setIndeterminateTintList(ColorStateList.valueOf(FG));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(DIM));
        box.addView(label);
        box.addView(bar);
        copyCancel.set(false);
        copying = true;
        paintItems();
        final OledDialog dialog = dialog(T("Copiando a ISO para o app"), null, box, new String[] {T("Cancelar")}, 0, 0,
            w -> copyCancel.set(true));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final File tmp = new File(target.getParentFile(), "twinsanity.iso.part");

        new Thread(() -> {
            String error = null;
            long copied = 0;
            final long start = System.nanoTime();
            try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(tmp, false)) {
                if (in == null) throw new java.io.IOException("the provider returned no stream");
                byte[] buf = new byte[4 * 1024 * 1024];
                long lastUi = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (copyCancel.get()) throw new java.io.IOException("cópia cancelada");
                    out.write(buf, 0, n);
                    copied += n;
                    long now = System.nanoTime();
                    if (now - lastUi > 250_000_000L) {
                        lastUi = now;
                        final long c = copied;
                        final double secs = (now - start) / 1e9;
                        runOnUiThread(() -> {
                            if (total > 0) bar.setProgress((int) (c * 1000 / total));
                            label.setText(displayName + "\n" + mb(c) + (total > 0 ? T(" de ") + mb(total) : "")
                                    + String.format(Locale.US, " — %.1f MB/s", c / 1048576.0 / Math.max(secs, 0.001)));
                        });
                    }
                }
                out.flush();
                out.getFD().sync();
                if (total > 0 && copied != total) throw new java.io.IOException("copiados " + copied + " bytes de " + total);
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            StringBuilder report = new StringBuilder();
            if (error == null) {
                String bad = Diag.checkDiscImage(tmp.getAbsolutePath(), report);
                if (bad != null) error = "o arquivo copiado não é uma imagem de DVD de PS2: " + bad;
            }
            if (error == null) {
                // the copy's name by what it is: a CHD (chdman createdvd) keeps its own name, and the other one goes
                File dest = Diag.isChd(tmp) ? Diag.importedChd(this) : target;
                target.delete();
                Diag.importedChd(this).delete();
                if (!tmp.renameTo(dest)) error = "não foi possível renomear a cópia";
            }
            if (error != null) tmp.delete();
            final String err = error;
            final long finalCopied = copied;
            final double secs = (System.nanoTime() - start) / 1e9;
            Diag.log(this, "main", err == null
                    ? "ISO copied: " + displayName + ", " + mb(finalCopied) + " in " + String.format(Locale.US, "%.0f s", secs) + " -> " + target + " (" + report + ")"
                    : "ISO copy FAILED after " + mb(finalCopied) + ": " + err);
            runOnUiThread(() -> {
                copying = false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                dialog.close();
                if (err == null) {
                    prefs().edit().putString("iso_source", displayName + " (" + mb(finalCopied) + ")").apply();
                    dialog(T("ISO pronta"),
                        displayName + T(" foi copiada e verificada:\n") + report + T("\n\nVocê pode apagar o arquivo original se quiser liberar espaço."),
                        null, new String[] {T("Iniciar jogo"), T("OK")}, 0, 1, w -> { if (w == 0) startGame(false); });
                } else {
                    dialog(T("A cópia da ISO falhou"), err, null, new String[] {T("OK")}, 0, 0, null);
                }
                refresh();
            });
        }, "te-iso-copy").start();
    }


    private void buildReport(final boolean share) {
        Toast.makeText(this, T("Gerando relatório…"), Toast.LENGTH_SHORT).show();
        final Context c = this;
        new Thread(() -> {
            File dir = Diag.logsDir(c);
            Diag.write(new File(dir, "logcat.txt"), Diag.captureLogcat(4000));
            StringBuilder b = new StringBuilder();
            b.append("TwinsanityEvolved Android — relatório de diagnóstico — ").append(Diag.now()).append("\n\n");
            b.append(statusText()).append('\n');
            String[] order = {"stage-game.txt", "java-crash-game.txt", "java-game.log", "latest-game.log", "previous-game.log",
                              "stage-probe.txt", "java-crash-probe.txt", "probe-result.txt", "latest-probe.log",
                              "java-crash-main.txt", "java-main.log", "logcat.txt"};
            for (String name : order) {
                String content = Diag.readFile(new File(dir, name), name.equals("logcat.txt") ? 300000 : 120000);
                if (content == null) continue;
                b.append("\n===== ").append(name).append(" =====\n").append(content).append('\n');
            }
            String game = Diag.readFile(new File(dir, "latest-game.log"), 0);
            if (game != null) {
                StringBuilder in = new StringBuilder(), au = new StringBuilder();
                for (String line : game.split("\n")) {
                    if (line.contains("input:") || line.contains("] input")) in.append(line).append('\n');
                    if (line.contains("audio") || line.contains("AAudio")) au.append(line).append('\n');
                }
                b.append("\n===== CONTROLES (linhas de input do latest-game.log) =====\n").append(in.length() > 0 ? in : "(nenhuma)\n");
                b.append("\n===== ÁUDIO (linhas de áudio do latest-game.log) =====\n").append(au.length() > 0 ? au : "(nenhuma)\n");
                StringBuilder gx = new StringBuilder();
                for (String line : game.split("\n"))
                    if (line.contains("[gl") || line.contains("display:") || line.contains("particles") || line.contains("TE_GFX")
                            || line.contains("te.gfx.capture") || line.contains("te.gfx.submit"))
                        gx.append(line).append('\n');
                b.append("\n===== GRÁFICOS ([gl], [gl.es], [gl.debug], display:, partículas) =====\n").append(gx.length() > 0 ? gx : "(nenhuma)\n");
                StringBuilder pf = new StringBuilder();
                for (String line : game.split("\n"))
                    if (line.contains("[te.gfx] pace") || line.contains("[te.gfx] lod") || line.contains("[te.android] perf") || line.contains("android settings:")
                            || line.contains("] display: aspect"))
                        pf.append(line).append('\n');
                b.append("\n===== DESEMPENHO (fps, tempo de quadro, CPU, memória, configurações) =====\n").append(pf.length() > 0 ? pf : "(nenhuma)\n");
            }
            String frames = Diag.symbolizeFrames(c, b.toString());
            if (!frames.isEmpty()) b.append("\n===== libmain.so frames, symbolised (installed libmain.so .symtab) =====\n").append(frames);
            final String report = b.toString();
            Diag.write(new File(dir, "report.txt"), report);
            runOnUiThread(() -> {
                refresh();
                if (share) {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_SUBJECT, "TwinsanityEvolved Android — relatório");
                    send.putExtra(Intent.EXTRA_STREAM, LogProvider.uriFor("report.txt"));
                    send.putExtra(Intent.EXTRA_TEXT, "Relatório de diagnóstico do TwinsanityEvolved (anexo report.txt).");
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, "Compartilhar relatório"));
                } else {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    String clip = report.length() > 250000 ? report.substring(report.length() - 250000) : report;
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("TwinsanityEvolved report", clip));
                    Toast.makeText(c, T("Relatório copiado (") + clip.length() + T(" caracteres)"), Toast.LENGTH_LONG).show();
                }
            });
        }, "te-report").start();
    }
}

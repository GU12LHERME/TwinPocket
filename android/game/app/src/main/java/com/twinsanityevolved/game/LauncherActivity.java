package com.twinsanityevolved.game;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.StatFs;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
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

import static com.twinsanityevolved.game.Lang.T;

/**
 * The app's entry (launcher icon), in the app's main process, with no native code and no SDL. A landscape screen for a
 * handheld: on the left the game (the chosen disc image, its state, a large JOGAR button, the ISO picker and what the
 * last start did); on the right the settings by category (Gráficos, Áudio, Controles, Sistema) and the Diagnóstico
 * page (the report, the probe, the SDL test, the diagnostic mode).
 *
 * The settings are the game's own, in ps2r.ini (Settings.java): the game's menus (Options > graphic options, Options >
 * android settings) and this screen edit the same keys, and every change here is written at once.
 *
 * Controller: the D-pad moves between the rows (Android's focus search), left / right change a setting, A chooses (the
 * system's fallback of BUTTON_A to DPAD_CENTER), L1 / R1 change category, START plays, B comes back to JOGAR.
 */
public final class LauncherActivity extends Activity {
    private static final int REQUEST_PICK_ISO = 41;
    private final AtomicBoolean copyCancel = new AtomicBoolean(false);
    private boolean copying;
    private boolean starting;

    // the screen
    private Button playButton;
    private TextView isoName, isoState, lastRun;
    private LinearLayout tabBar, page;
    private ScrollView pageScroll;
    private final List<Button> tabs = new ArrayList<>();
    private int currentTab = 0;
    private TextView status;   // the Diagnóstico page's text (null on the other pages)

    // the palette
    static final int BG = 0xFF101318, PANEL = 0xFF181C23, ROW = 0xFF1F242D, ROW_FOCUS = 0xFF2B3240, TEXT = 0xFFEDEFF3,
            SUBTLE = 0xFF9AA3B2, ACCENT = 0xFFFF8A1F, ACCENT_DARK = 0xFFD86A00, GOOD = 0xFF4CC38A, BAD = 0xFFFF5D5D;

    // The audio driver a game starts with when the player chose none: OpenSL ES -- the one that played cleanly on the
    // Retroid Pocket 6 (SDL's own order, AAudio first, crackled there). Every driver stays selectable.
    static final String DEFAULT_AUDIO_DRIVER = "openslES";

    // Diagnostic mode (off by default): the next game start logs the GPU driver's messages (TE_DIAG_GL=1) and arms the
    // L3 + R3 draw capture (TE_DIAG_CAPTURE=1) and logs the sticks (TE_INPUT_LOG=1), for a report about a graphics or a
    // controls problem. Off, none of them costs anything.
    static final String[] DIAGNOSTIC_ENV = {"TE_DIAG_GL=1", "TE_DIAG_CAPTURE=1", "TE_INPUT_LOG=1"};

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
        new Choice(Haptics.KEY, "Vibração no launcher", "Um toque curto ao confirmar e ao trocar de aba (o motor do aparelho)",
            "on", new String[] {"on", "off"}, new String[] {"Ligada — padrão", "Desligada"}),
    };
    static final Choice LANGUAGE = new Choice(Lang.KEY, "Idioma / Language", null, "auto", new String[] {"auto", "pt-BR", "en"},
        new String[] {"Automático (idioma do sistema)", "Português (Brasil)", "English"});
    static final String[] TAB_NAMES = {"Gráficos", "Áudio", "Controles", "Sistema", "Diagnóstico"};

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private static GradientDrawable rounded(int color, float radius, int strokeWidth, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        if (strokeWidth > 0) g.setStroke(strokeWidth, strokeColor);
        return g;
    }

    // a background that shows where the controller's focus is (an orange outline) and a press
    private Drawable focusable(int normal, int focused) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_pressed}, rounded(focused, dp(10), dp(2), ACCENT));
        s.addState(new int[] {android.R.attr.state_focused}, rounded(focused, dp(10), dp(2), ACCENT));
        s.addState(new int[] {}, rounded(normal, dp(10), 0, 0));
        return s;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(T(s));   // (a text not in the table -- a file name, a status line -- stays as it is)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String label, int normal, int focusedColor, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(T(label));
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setBackground(focusable(normal, focusedColor));
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        b.setFocusable(true);
        b.setOnClickListener(l);
        if (Build.VERSION.SDK_INT >= 21) b.setStateListAnimator(null);
        return b;
    }

    private LinearLayout.LayoutParams fill(int height, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
        p.topMargin = top;
        return p;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Diag.installCrashHandler(this, "main");
        new Settings(this).migrate(prefs());
        Lang.init(this);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));

        // ---- left: the game ----
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setBackground(rounded(PANEL, dp(16), 0, 0));
        left.setPadding(dp(20), dp(18), dp(20), dp(18));
        left.addView(text("Crash Twinsanity", 26, ACCENT, true));
        left.addView(text("TwinsanityEvolved · port nativo Android", 13, SUBTLE, false));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(ROW, dp(12), 0, 0));
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        card.addView(text("JOGO SELECIONADO", 11, SUBTLE, true));
        isoName = text("—", 15, TEXT, true);
        isoState = text("", 13, SUBTLE, false);
        card.addView(isoName);
        card.addView(isoState);
        left.addView(card, fill(ViewGroup.LayoutParams.WRAP_CONTENT, dp(14)));

        playButton = button("JOGAR", ACCENT_DARK, ACCENT, v -> startGame(false));
        playButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        playButton.setTypeface(Typeface.DEFAULT_BOLD);
        left.addView(playButton, fill(dp(64), dp(14)));
        Button pick = button("Selecionar ISO…", ROW, ROW_FOCUS, v -> pickIso());
        left.addView(pick, fill(dp(48), dp(10)));
        Button exit = button("Sair do aplicativo", ROW, ROW_FOCUS, v -> confirmExit());
        left.addView(exit, fill(dp(44), dp(8)));

        lastRun = text("", 12, SUBTLE, false);
        left.addView(lastRun, fill(ViewGroup.LayoutParams.WRAP_CONTENT, dp(12)));
        View spacer = new View(this);
        left.addView(spacer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        left.addView(text("A escolher · ◀ ▶ mudar · L1/R1 categoria · START jogar · B voltar · no jogo: Opções → android settings → return to launcher", 11, SUBTLE, false));
        left.addView(text(T("versão ") + versionName(), 11, SUBTLE, false));

        ScrollView leftScroll = new ScrollView(this);
        leftScroll.setFillViewport(true);
        leftScroll.addView(left);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.4f);
        lp.rightMargin = dp(16);
        root.addView(leftScroll, lp);

        // ---- right: the settings by category, and the diagnostics ----
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setBackground(rounded(PANEL, dp(16), 0, 0));
        right.setPadding(dp(14), dp(12), dp(14), dp(12));
        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < TAB_NAMES.length; i++) {
            final int index = i;
            Button t = button(TAB_NAMES[i], PANEL, ROW_FOCUS, v -> showTab(index));
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, dp(44), 1f);
            tp.rightMargin = dp(4);
            tabBar.addView(t, tp);
            tabs.add(t);
        }
        HorizontalFit.wrap(tabBar);
        right.addView(tabBar);
        pageScroll = new ScrollView(this);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, dp(8), 0, dp(8));
        pageScroll.addView(page);
        right.addView(pageScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.6f));

        setContentView(root);
        currentTab = Math.max(0, Math.min(TAB_NAMES.length - 1, prefs().getInt("launcher_tab", 0)));
        showTab(currentTab);
        playButton.requestFocus();
    }

    /** Keeps the category buttons on one line on a narrow screen: their text shrinks rather than wrapping. */
    static final class HorizontalFit {
        static void wrap(LinearLayout bar) {
            for (int i = 0; i < bar.getChildCount(); i++) {
                View v = bar.getChildAt(i);
                if (v instanceof TextView) {
                    ((TextView) v).setSingleLine(true);
                    ((TextView) v).setEllipsize(android.text.TextUtils.TruncateAt.END);
                }
            }
        }
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
        playButton.setEnabled(true);
        playButton.setText(T("JOGAR"));
        refresh();
    }

    // ---- the controller -------------------------------------------------------------------------------------------------
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_BUTTON_L1:
                    Haptics.tap(this, e, false);
                    showTab((currentTab + TAB_NAMES.length - 1) % TAB_NAMES.length);
                    tabs.get(currentTab).requestFocus();
                    return true;
                case KeyEvent.KEYCODE_BUTTON_R1:
                    Haptics.tap(this, e, false);
                    showTab((currentTab + 1) % TAB_NAMES.length);
                    tabs.get(currentTab).requestFocus();
                    return true;
                case KeyEvent.KEYCODE_BUTTON_START:
                    Haptics.tap(this, e, true);
                    startGame(false);
                    return true;
                case KeyEvent.KEYCODE_BUTTON_A:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    // a confirm on a focused control (the press goes on to the view as before)
                    if (getCurrentFocus() != null) Haptics.tap(this, e, true);
                    break;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBackPressed() {
        // B (the system's fallback to BACK) first brings the focus back to JOGAR; from there it leaves
        if (!playButton.isFocused()) {
            playButton.requestFocus();
            return;
        }
        super.onBackPressed();
    }

    // ---- the pages ------------------------------------------------------------------------------------------------------
    private void showTab(int index) {
        currentTab = index;
        prefs().edit().putInt("launcher_tab", index).apply();
        for (int i = 0; i < tabs.size(); i++) {
            Button t = tabs.get(i);
            boolean on = i == index;
            t.setBackground(focusable(on ? ROW : PANEL, ROW_FOCUS));
            t.setTextColor(on ? ACCENT : SUBTLE);
            t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        page.removeAllViews();
        status = null;
        Settings st = new Settings(this);
        switch (index) {
            case 0:
                page.addView(presetRow(st));
                for (Choice c : GRAPHICS) page.addView(choiceRow(st, c));
                note("As mesmas opções ficam no jogo: Opções → Opções gráficas.");
                break;
            case 1:
                for (Choice c : AUDIO) page.addView(choiceRow(st, c));
                note(T("O volume dos efeitos e da música fica no menu do jogo (Opções → Opções de som). ")
                        + T("Driver e buffer também estão no jogo: Opções → android settings."));
                break;
            case 2:
                for (Choice c : CONTROLS) page.addView(choiceRow(st, c));
                note("Os analógicos mudam na hora, também pelo jogo (Opções → android settings).");
                note("BOTÕES — escolha um botão do PS2 e aperte o botão do controle que deve fazer o papel dele. Se o botão "
                        + "já estiver em uso, os dois trocam de lugar. Vale no próximo início do jogo; o launcher não muda.");
                for (int i = 0; i < PAD_BUTTONS.length; i++) page.addView(padRow(st, i));
                page.addView(actionRow("Restaurar botões padrão", "O layout do controle volta ao do DualShock 2", v -> confirmPadRestore()));
                break;
            case 3:
                page.addView(choiceRow(st, LANGUAGE));
                note("O launcher muda na hora; as opções do port dentro do jogo ficam em inglês (a fonte do jogo).");
                page.addView(actionRow("Restaurar apenas gráficos",
                    "Para tela preta ou falha ao iniciar: só as opções gráficas voltam ao padrão (áudio, controles, idioma e saves ficam)",
                    v -> confirmRestoreGraphics()));
                page.addView(actionRow("Restaurar configurações padrão", "Gráficos, áudio e controles voltam aos valores recomendados", v -> confirmRestore()));
                note(T("Arquivo de configurações (compartilhado com o jogo):\n") + st.file().getAbsolutePath());
                note(T("Versão ") + versionName() + " · " + Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE
                        + " (API " + Build.VERSION.SDK_INT + ") · " + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?"));
                break;
            default:
                page.addView(actionRow("Compartilhar relatório", "Um arquivo de texto com os logs da última execução", v -> buildReport(true)));
                page.addView(actionRow("Copiar relatório", "O mesmo relatório para a área de transferência", v -> buildReport(false)));
                final boolean diag = prefs().getBoolean("diagnostic_mode", false);
                page.addView(actionRow(T("Modo diagnóstico: ") + T(diag ? "LIGADO" : "desligado"),
                        "Registra as mensagens da GPU e os analógicos e permite capturar um quadro com L3 + R3 (deixe desligado para jogar)", v -> {
                            prefs().edit().putBoolean("diagnostic_mode", !diag).apply();
                            Diag.log(this, "main", "diagnostic mode " + (!diag ? "on" : "off"));
                            showTab(4);
                        }));
                page.addView(actionRow("Diagnóstico sem o jogo", "Carrega as bibliotecas nativas e testa o ambiente", v -> startActivity(new Intent(this, ProbeActivity.class))));
                page.addView(actionRow("Teste do SDL sem o jogo", "Abre a janela, o controle e o áudio sem a ISO", v -> startSdlTest()));
                status = text("", 11, SUBTLE, false);
                status.setTypeface(Typeface.MONOSPACE);
                status.setTextIsSelectable(true);
                status.setPadding(dp(6), dp(10), dp(6), dp(6));
                page.addView(status);
                status.setText(statusText());
                break;
        }
        pageScroll.scrollTo(0, 0);
    }

    private void note(String s) {
        TextView t = text(s, 12, SUBTLE, false);
        t.setPadding(dp(6), dp(12), dp(6), 0);
        page.addView(t);
    }

    private View rowFrame(String label, String help, TextView value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(focusable(ROW, ROW_FOCUS));
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        row.setFocusable(true);
        row.setClickable(true);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(text(label, 15, TEXT, true));
        if (help != null) words.addView(text(help, 12, SUBTLE, false));
        row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (value != null) row.addView(value);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(6);
        row.setLayoutParams(p);
        return row;
    }

    private View actionRow(String label, String help, View.OnClickListener l) {
        View row = rowFrame(label, help, text("›", 20, ACCENT, true));
        row.setOnClickListener(l);
        return row;
    }

    // A setting: tap / A = the next value, left / right on the controller = the previous / next; written at once
    private View choiceRow(final Settings st, final Choice c) {
        final TextView value = text("", 14, ACCENT, true);
        value.setPadding(dp(12), 0, 0, 0);
        final int[] at = {c.index(st.get(c.key, c.def))};
        final Runnable show = () -> value.setText("◀  " + T(c.names[at[0]]) + "  ▶");
        show.run();
        final View row = rowFrame(c.label, c.help, value);
        final java.util.function.IntConsumer step = d -> {
            at[0] = (at[0] + d + c.values.length) % c.values.length;
            if (st.set(c.key, c.values[at[0]])) {
                Diag.log(this, "main", "setting " + c.key + " = " + c.values[at[0]]);
            } else {
                Toast.makeText(this, T("Não foi possível salvar em ") + st.file(), Toast.LENGTH_LONG).show();
            }
            show.run();
            if (Lang.KEY.equals(c.key)) {   // the launcher's language: the screen is built again in it
                Lang.init(this);
                recreate();
            }
        };
        row.setOnClickListener(v -> step.accept(1));
        row.setOnKeyListener((v, keyCode, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { step.accept(-1); return true; }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { step.accept(1); return true; }
            return false;
        });
        return row;
    }

    // Leaving the app (not the same as the game's "return to launcher", which ends a game session and comes back here): the
    // launcher's task is finished and removed from the recent apps. A game or diagnostic process still running (normally
    // none while this screen is shown: the game's process ends when its activity finishes) is ended first, so nothing of
    // the app keeps running. The settings, the saves, the ISO and the logs are files: nothing is lost.
    private void confirmExit() {
        final boolean gameAlive = Diag.processAlive(this, "game");
        new AlertDialog.Builder(this)
            .setTitle(T("Sair do aplicativo?"))
            .setMessage(T(gameAlive
                    ? "O jogo ainda está aberto em segundo plano. Saia pelo jogo (Opções → android settings → return to launcher) "
                      + "para não perder progresso não salvo. Sair mesmo assim encerra o jogo."
                    : "O TwinsanityEvolved será fechado. Configurações, saves e a ISO continuam guardados."))
            .setPositiveButton(T("Sair"), (d, w) -> exitApp())
            .setNegativeButton(T("Cancelar"), null)
            .show();
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

    private View padRow(final Settings st, final int i) {
        final TextView value = text("", 14, ACCENT, true);
        value.setPadding(dp(12), 0, 0, 0);
        value.setText(controlLabel(padControl(st, i)));   // (A, B, L1, ...: the same in both languages)
        View row = rowFrame(PAD_LABELS[i], null, value);
        row.setOnClickListener(v -> capturePad(st, i));
        return row;
    }

    // Press-to-bind: the next gamepad key is the PS2 button's control; a control already bound to another PS2 button
    // swaps with this one's (no button is ever left without a control). Cancel: the dialog's button (touch) or 6 s.
    private void capturePad(final Settings st, final int i) {
        final AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(T(PAD_LABELS[i]))
            .setMessage(T("Aperte agora o botão do controle para ") + T(PAD_LABELS[i]) + T(".\n(Atual: ") + controlLabel(padControl(st, i))
                    + T(". Cancela sozinho em 6 segundos.)"))
            .setNegativeButton(T("Cancelar"), null)
            .create();
        final android.os.Handler h = new android.os.Handler(getMainLooper());
        final Runnable timeout = () -> { if (dialog.isShowing()) dialog.dismiss(); };
        dialog.setOnKeyListener((d, keyCode, e) -> {
            String control = sdlControl(keyCode);
            if (control == null) return false;
            if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
            String old = padControl(st, i);
            boolean ok = true;
            for (int k = 0; k < PAD_BUTTONS.length; k++)
                if (k != i && padControl(st, k).equals(control)) ok &= st.set("android.pad." + PAD_BUTTONS[k], old);
            ok &= st.set("android.pad." + PAD_BUTTONS[i], control);
            Diag.log(this, "main", "pad remap: " + PAD_BUTTONS[i] + " = " + control + (ok ? "" : " (NOT written)"));
            h.removeCallbacks(timeout);
            dialog.dismiss();
            showTab(2);
            return true;
        });
        dialog.setOnDismissListener(d -> h.removeCallbacks(timeout));
        dialog.show();
        h.postDelayed(timeout, 6000);
    }

    private void confirmPadRestore() {
        new AlertDialog.Builder(this)
            .setTitle(T("Restaurar botões padrão?"))
            .setMessage(T("Todos os botões voltam ao layout do DualShock 2 (A = ✕, B = ○, X = □, Y = △...)."))
            .setPositiveButton(T("Restaurar"), (d, w) -> {
                Settings st = new Settings(this);
                for (int k = 0; k < PAD_BUTTONS.length; k++) st.set("android.pad." + PAD_BUTTONS[k], PAD_DEFAULTS[k]);
                Diag.log(this, "main", "pad remap restored to the standard layout");
                showTab(2);
            })
            .setNegativeButton(T("Cancelar"), null)
            .show();
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

    private View presetRow(final Settings st) {
        final TextView value = text("", 14, ACCENT, true);
        value.setPadding(dp(12), 0, 0, 0);
        final int now = presetNow(st);
        value.setText("◀  " + T(PRESET_NAMES[now]) + "  ▶");
        View row = rowFrame("Predefinição gráfica",
            "Original: como o jogo. Equilibrado: anisotrópica 4x, LOD 2x. Qualidade: anisotrópica 16x, LOD 4x. A resolução não muda (2x ainda não validado)",
            value);
        final java.util.function.IntConsumer step = d -> {
            int next = now == 3 ? (d > 0 ? 0 : 2) : (now + d + 3) % 3;
            st.set(Settings.TEXTURE_FILTER, "bilinear");
            st.set(Settings.ANISOTROPY, PRESETS[next][0]);
            st.set(Settings.DRAW_DISTANCE, PRESETS[next][1]);
            st.set(Settings.FXAA, "off");
            st.set(Settings.DITHERING, "off");
            Diag.log(this, "main", "graphics preset " + PRESET_NAMES[next]);
            showTab(0);
        };
        row.setOnClickListener(v -> step.accept(1));
        row.setOnKeyListener((v, keyCode, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { step.accept(-1); return true; }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { step.accept(1); return true; }
            return false;
        });
        return row;
    }

    private void confirmRestore() {
        new AlertDialog.Builder(this)
            .setTitle(T("Restaurar configurações padrão?"))
            .setMessage(T("Widescreen 16:9, VSync ligado, filtro bilinear, anisotrópica, FXAA e dithering desligados, resolução 1x, 60 FPS, contador desligado, LOD original, OpenSL ES com ")
                    + T("buffer 2048, analógicos no padrão. A ISO e os saves não mudam; a opção widescreen do jogo fica no save (Opções → Opções gráficas)."))
            .setPositiveButton(T("Restaurar"), (d, w) -> {
                Settings st = new Settings(this);
                boolean ok = true;
                for (Choice[] group : new Choice[][] {GRAPHICS, AUDIO, CONTROLS})
                    for (Choice c : group) ok &= st.set(c.key, c.def);
                Diag.log(this, "main", "settings restored to the defaults (" + (ok ? "written" : "NOT all written") + ")");
                Toast.makeText(this, ok ? T("Configurações padrão restauradas") : T("Falha ao salvar em ") + st.file(), Toast.LENGTH_LONG).show();
                showTab(currentTab);
            })
            .setNegativeButton(T("Cancelar"), null)
            .show();
    }

    // ---- the game's state on the left -----------------------------------------------------------------------------------
    private void refresh() {
        String source = prefs().getString("iso_source", null);
        String[] disc = Diag.findDiscImage(this);
        if (disc[0] == null) {
            isoName.setText(T("Nenhuma ISO selecionada"));
            isoState.setText(T("Toque em \"Selecionar ISO…\" e escolha a SUA imagem do Crash Twinsanity (PAL ou NTSC-U)."));
            isoState.setTextColor(BAD);
        } else {
            StringBuilder r = new StringBuilder();
            String err = Diag.checkDiscImage(disc[0], r);
            isoName.setText(source != null ? source : new File(disc[0]).getName());
            isoState.setText(err == null ? T("Pronta — ") + r : T("Não utilizável: ") + err);
            isoState.setTextColor(err == null ? GOOD : BAD);
        }
        lastRun.setText(T(lastRunLine()));
        if (status != null) status.setText(statusText());
    }

    // what the last start did, in one line (the Diagnóstico page has the detail)
    private String lastRunLine() {
        File f = new File(Diag.logsDir(this), "stage-game.txt");
        String s = Diag.readFile(f, 2000);
        if (s == null) return "Ainda não executado nesta instalação.";
        s = s.trim();
        if (s.startsWith("running")) {
            if (Diag.processAlive(this, "game")) return "O jogo está em execução.";
            lastRun.setTextColor(BAD);
            return "A última execução terminou de forma inesperada. Veja Diagnóstico → Compartilhar relatório.";
        }
        if (s.startsWith("crash")) {
            lastRun.setTextColor(BAD);
            return "A última execução travou. Veja Diagnóstico → Compartilhar relatório.";
        }
        lastRun.setTextColor(SUBTLE);
        return "A última execução terminou normalmente.";
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
            new AlertDialog.Builder(this)
                .setTitle(T("Imagem do disco não disponível"))
                .setMessage(T("O jogo precisa da SUA imagem do Crash Twinsanity (PAL SLES-52568 ou NTSC-U SLUS-20909 v2.00).\n\n")
                        + T("Toque em \"Selecionar ISO\" e escolha o arquivo no gerenciador de arquivos do Android.\n\n")
                        + T("Detalhe: ") + err)
                .setPositiveButton(T("Selecionar ISO"), (d, w) -> pickIso())
                .setNeutralButton(T("Cancelar"), null)
                .setNegativeButton(T("Iniciar mesmo assim"), (d, w) -> startGame(true))
                .show();
            return;
        }
        starting = true;
        playButton.setText(T("Iniciando…"));
        playButton.setEnabled(false);
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
            startActivityForResult(i, REQUEST_PICK_ISO);
        } catch (Exception e) {
            Diag.log(this, "main", "ACTION_OPEN_DOCUMENT failed: " + e);
            Toast.makeText(this, T("O seletor de arquivos do Android não abriu: ") + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_ISO) return;
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
            new AlertDialog.Builder(this).setTitle(T("Espaço insuficiente"))
                .setMessage(T("A ISO tem ") + mb(size) + T(" e o armazenamento interno tem ") + mb(free + existing) + T(" livres. Libere espaço e tente de novo."))
                .setPositiveButton(T("OK"), null).show();
            Diag.log(this, "main", "ISO copy refused: " + mb(size) + " needed, " + mb(free + existing) + " free");
            return;
        }

        final String displayName = name;
        final long total = size;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        final TextView label = new TextView(this);
        label.setText(T("Copiando ") + displayName + "…");
        final ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setIndeterminate(total <= 0);
        box.addView(label);
        box.addView(bar);
        copyCancel.set(false);
        final AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(T("Copiando a ISO para o app"))
            .setView(box)
            .setCancelable(false)
            .setNegativeButton(T("Cancelar"), (d, w) -> copyCancel.set(true))
            .show();
        copying = true;
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
                dialog.dismiss();
                if (err == null) {
                    prefs().edit().putString("iso_source", displayName + " (" + mb(finalCopied) + ")").apply();
                    new AlertDialog.Builder(this).setTitle(T("ISO pronta"))
                        .setMessage(displayName + T(" foi copiada e verificada:\n") + report + T("\n\nVocê pode apagar o arquivo original se quiser liberar espaço."))
                        .setPositiveButton(T("Iniciar jogo"), (d, w) -> startGame(false))
                        .setNegativeButton(T("OK"), null).show();
                } else {
                    new AlertDialog.Builder(this).setTitle(T("A cópia da ISO falhou"))
                        .setMessage(err).setPositiveButton(T("OK"), null).show();
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

package com.twinsanityevolved.game;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * The app's entry (launcher icon), in the app's main process, with no native code and no SDL: it shows what the last
 * start reached (the stage files and logs the native and Java halves write in files/logs), whether the disc image is in
 * place, and starts the game (MainActivity, ":game" process), the SDL test (MainActivity with no game) or the diagnostic
 * (ProbeActivity, ":probe" process). Because the game runs in another process, a crash there brings the player back here
 * with the stage it died in. "Compartilhar relatório" puts everything into one text file for the share sheet.
 */
public final class LauncherActivity extends Activity {
    private TextView status;
    private Button startButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Diag.installCrashHandler(this, "main");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("TwinsanityEvolved — Android (diagnóstico)");
        title.setTextSize(20);
        root.addView(title);

        LinearLayout row1 = new LinearLayout(this);
        LinearLayout row2 = new LinearLayout(this);
        startButton = addButton(row1, "Iniciar jogo", v -> startGame(false));
        addButton(row1, "Diagnóstico (sem o jogo)", v -> startActivity(new Intent(this, ProbeActivity.class)));
        addButton(row1, "Teste SDL (sem o jogo)", v -> startSdlTest());
        addButton(row2, "Compartilhar relatório", v -> buildReport(true));
        addButton(row2, "Copiar relatório", v -> buildReport(false));
        addButton(row2, "Atualizar", v -> refresh());
        root.addView(row1);
        root.addView(row2);

        status = new TextView(this);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextSize(12);
        status.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(status);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        startButton.requestFocus();
    }

    private Button addButton(LinearLayout row, String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        row.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
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
        b.append("IMAGEM DO DISCO\n  pasta: ").append(ext != null ? ext.getAbsolutePath() : "(indisponível)").append('\n');
        String[] disc = Diag.findDiscImage(ext);
        if (disc[0] == null) {
            b.append("  NENHUMA imagem encontrada — ").append(disc[1]).append('\n')
             .append("  Copie a SUA imagem do jogo para essa pasta com o nome twinsanity.iso\n");
        } else {
            StringBuilder r = new StringBuilder();
            String err = Diag.checkDiscImage(disc[0], r);
            b.append("  ").append(disc[0]).append(" (").append(disc[1]).append(")\n  ").append(r)
             .append(err == null ? " — OK" : " — NÃO UTILIZÁVEL: " + err).append('\n');
        }
        b.append('\n').append("ÚLTIMAS EXECUÇÕES\n");
        b.append(describeStage("game", "  jogo / teste SDL"));
        b.append(describeStage("probe", "  diagnóstico"));

        String probe = Diag.readFile(new File(Diag.logsDir(this), "probe-result.txt"), 6000);
        if (probe != null && !probe.isEmpty()) b.append("\nÚLTIMO DIAGNÓSTICO\n").append(probe).append('\n');

        String java = Diag.tailLines(new File(Diag.logsDir(this), "java-game.log"), 12);
        if (java != null) b.append("\nLOG JAVA DO JOGO (fim)\n").append(java);
        String nat = Diag.tailLines(new File(Diag.logsDir(this), "latest-game.log"), 40);
        if (nat != null) b.append("\nLOG NATIVO DO JOGO (fim)\n").append(nat);
        b.append("\nLogs: ").append(Diag.logsDir(this).getAbsolutePath()).append('\n');
        return b.toString();
    }

    private void refresh() {
        status.setText(statusText());
    }

    private void startGame(boolean force) {
        String[] disc = Diag.findDiscImage(getExternalFilesDir(null));
        StringBuilder r = new StringBuilder();
        String err = disc[0] == null ? disc[1] : Diag.checkDiscImage(disc[0], r);
        if (err != null && !force) {
            File ext = getExternalFilesDir(null);
            new AlertDialog.Builder(this)
                .setTitle("Imagem do disco não encontrada")
                .setMessage("O jogo precisa da SUA imagem do Crash Twinsanity (PAL SLES-52568 ou NTSC-U SLUS-20909 v2.00).\n\n"
                        + "Copie o arquivo para:\n" + (ext != null ? ext.getAbsolutePath() : "(pasta indisponível)") + "/twinsanity.iso\n\n"
                        + "Detalhe: " + err)
                .setPositiveButton("OK", null)
                .setNegativeButton("Iniciar mesmo assim", (d, w) -> startGame(true))
                .show();
            return;
        }
        Diag.log(this, "main", "starting the game (disc: " + disc[0] + ")");
        startActivity(new Intent(this, MainActivity.class));
    }

    private void startSdlTest() {
        Diag.log(this, "main", "starting the SDL test (no game)");
        Intent i = new Intent(this, MainActivity.class);
        i.putExtra(MainActivity.EXTRA_SDL_TEST, true);
        startActivity(i);
    }

    private void buildReport(final boolean share) {
        Toast.makeText(this, "Gerando relatório…", Toast.LENGTH_SHORT).show();
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
                    Toast.makeText(c, "Relatório copiado (" + clip.length() + " caracteres)", Toast.LENGTH_LONG).show();
                }
            });
        }, "te-report").start();
    }
}

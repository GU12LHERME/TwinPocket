package com.twinsanityevolved.game;

import android.content.Context;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The launcher's languages: Português (Brasil) and English, in one table. The launcher's texts are written in Portuguese
 * at their call sites and pass through T(); in English the table gives each one's translation (a text missing from the
 * table stays Portuguese, never blank). The choice: ps2r.ini android.ui_language = auto | pt-BR | en (Settings.java);
 * auto -- the default, and a first run -- follows the system's language: Portuguese for any pt locale, else English.
 * The diagnostic report and the logs stay as they are (technical text for the developer).
 */
final class Lang {
    static final String KEY = "android.ui_language";
    private static boolean english = false;

    private Lang() {}

    static void init(Context c) {
        String choice = new Settings(c).get(KEY, "auto");
        if ("en".equals(choice)) english = true;
        else if ("pt-BR".equals(choice)) english = false;
        else english = !"pt".equals(Locale.getDefault().getLanguage());
    }

    static boolean english() {
        return english;
    }

    /** The text in the launcher's language (the Portuguese text is the key). */
    static String T(String pt) {
        if (!english || pt == null) return pt;
        String en = EN.get(pt);
        return en != null ? en : pt;
    }

    private static final Map<String, String> EN = new HashMap<>();

    private static void e(String pt, String en) {
        EN.put(pt, en);
    }

    static {
        // tabs, the game's card, the buttons
        e("Gráficos", "Graphics");
        e("Controles", "Controls");
        e("Sistema", "System");
        e("JOGAR", "PLAY");
        e("Iniciando…", "Starting…");
        e("Selecionar", "Select");
        e("Sair do aplicativo", "Exit application");
        e("JOGO SELECIONADO", "SELECTED GAME");
        e("Jogar", "Play");
        e("Copia a SUA imagem do jogo (ISO ou CHD) para o app, sem permissão de armazenamento", "Copies YOUR game image (ISO or CHD) into the app, no storage permission");
        e("RESTAURAR", "RESTORE");
        e("Licenças de código aberto", "Open source licenses");
        e("Os projetos em que este port se baseia e as suas licenças", "The projects this port is built on and their licenses");
        e("Este port usa código de projetos de código aberto, cujas licenças pedem que o aviso de copyright acompanhe as cópias:\n\n",
          "This port uses code from open source projects whose licenses ask that the copyright notice travel with copies:\n\n");
        e("Nenhum dado do jogo faz parte do aplicativo: o jogo usa a SUA imagem do disco.", "No game data is part of the app: the game uses YOUR disc image.");
        e("Saves", "Saves");
        e("BACKUP", "BACKUP");
        e("SAVES NO CARTÃO", "SAVES ON THE CARD");
        e("Exportar backup", "Export backup");
        e("Salva todos os cartões de memória num arquivo .zip onde você escolher", "Saves every memory card to a .zip file wherever you choose");
        e("Importar backup", "Import backup");
        e("Restaura um .zip exportado (os cartões atuais ganham um backup automático antes)", "Restores an exported .zip (the current cards are backed up automatically first)");
        e("Nenhum save ainda. Os saves aparecem aqui depois de salvar dentro do jogo.", "No saves yet. Saves show up here after saving in the game.");
        e("cartão principal", "main card");
        e("cartão dos mods", "mods card");
        e("Nenhum gerenciador de arquivos disponível.", "No file manager available.");
        e("O jogo está aberto", "The game is open");
        e("Saia do jogo (Menu principal → quit game) antes de mexer nos saves: o jogo grava no cartão de memória.",
          "Leave the game (Main menu → quit game) before touching the saves: the game writes the memory card.");
        e("Apagar save?", "Delete save?");
        e("Um backup automático de todos os cartões é feito antes, em save-backups.", "An automatic backup of every card is made first, in save-backups.");
        e("Apagar", "Delete");
        e("O backup automático falhou: nada foi apagado.", "The automatic backup failed: nothing was deleted.");
        e("Save apagado", "Save deleted");
        e("Não foi possível apagar tudo", "Could not delete everything");
        e("Backup exportado: ", "Backup exported: ");
        e(" arquivo(s)", " file(s)");
        e("A exportação falhou: ", "Export failed: ");
        e("Importar backup?", "Import backup?");
        e("Os cartões de memória que estão no arquivo substituem os atuais. Um backup automático dos atuais é feito antes, em save-backups.",
          "The memory cards in the file replace the current ones. An automatic backup of the current ones is made first, in save-backups.");
        e("Importar", "Import");
        e("Backup importado: ", "Backup imported: ");
        e("A importação falhou: ", "Import failed: ");
        e("IMAGEM", "PICTURE");
        e("QUALIDADE", "QUALITY");
        e("ANALÓGICOS", "STICKS");
        e("VIBRAÇÃO", "VIBRATION");
        e("IDIOMA", "LANGUAGE");
        e("APLICATIVO", "APPLICATION");
        e("BOTÕES", "BUTTONS");
        e("Modo diagnóstico", "Diagnostic mode");
        e("Registra as mensagens da GPU, os analógicos e a vibração do jogo e permite capturar um quadro com L3 + R3 (deixe desligado para jogar)",
          "Logs the GPU messages, the sticks and the game's vibration and lets L3 + R3 capture a frame (leave off to play)");
        e("Fecha o Twinsanity Pocket (configurações, saves e a ISO ficam guardados)", "Closes Twinsanity Pocket (settings, saves and the ISO are kept)");
        e("versão ", "version ");
        e("Nenhuma ISO selecionada", "No ISO selected");
        e("Escolha \"Selecionar\" e indique a SUA imagem do Crash Twinsanity (PAL ou NTSC-U).",
          "Choose \"Select\" and point to YOUR Crash Twinsanity disc image (PAL or NTSC-U).");
        e("Pronta — ", "Ready — ");
        e("Não utilizável: ", "Not usable: ");
        e("Ainda não executado nesta instalação.", "Not run yet on this installation.");
        e("O jogo está em execução.", "The game is running.");
        e("A última execução terminou normalmente.", "The last run ended normally.");

        // graphics
        e("Filtragem anisotrópica", "Anisotropic filtering");
        e("Texturas do chão e paredes mais nítidas em ângulo; limitada ao máximo do driver (o log diz o aplicado)",
          "Sharper floor and wall textures at an angle; limited to the driver's maximum (the log says what was applied)");
        e("Desligada — padrão", "Off — default");
        e("Resolução interna", "Internal resolution");
        e("Mais alta: imagem mais nítida e mais trabalho para a GPU", "Higher: a sharper picture and more work for the GPU");
        e("1x (nativa do PS2)", "1x (the PS2's own)");
        e("Proporção da tela", "Aspect ratio");
        e("Original (4:3)", "Original (4:3)");
        e("Widescreen (16:9) — padrão", "Widescreen (16:9) — default");
        e("Taxa de quadros", "Frame rate");
        e("Quantos quadros são mostrados por segundo; a velocidade do jogo não muda", "How many frames are shown a second; the game's speed does not change");
        e("60 FPS — padrão", "60 FPS — default");
        e("VSync", "VSync");
        e("Sincroniza com a tela; desligue se notar engasgos no ritmo dos quadros", "Syncs with the screen; turn it off if the frame pacing stutters");
        e("Desligado", "Off");
        e("Ligado — padrão", "On — default");
        e("Contador de FPS", "FPS counter");
        e("Mostra o FPS no canto da tela durante o jogo", "Shows the FPS in a corner of the screen while playing");
        e("Ligado", "On");
        e("Mantém os modelos detalhados por mais distância, só entre os que o jogo já carregou. Exige mais da GPU",
          "Keeps the detailed models farther away, only among what the game has loaded. More work for the GPU");
        e("Original (1x)", "Original (1x)");
        e("Média (2x)", "Medium (2x)");
        e("Longa (3x)", "Long (3x)");
        e("As mesmas opções ficam no jogo: Opções → Opções gráficas.", "The same options are in the game: Options → graphic options.");

        // audio

        // controls
        e("Zona morta dos analógicos", "Stick deadzone");
        e("Vibração no launcher", "Launcher vibration");
        e("Vibração no jogo", "In-game vibration");
        e("A vibração original do jogo (precisa da opção Vibração do jogo ligada). Automática: motor do controle, ou o do aparelho",
          "The game's original vibration (needs the game's own Vibration option on). Automatic: the controller's motor, else the device's");
        e("Automática — padrão", "Automatic — default");
        e("Só o motor do controle", "Controller motor only");
        e("Um toque curto ao confirmar e ao trocar de aba (o motor do aparelho)", "A short tap on confirm and on tab change (the device's motor)");
        e("Ligada — padrão", "On — default");
        e("Desligada", "Off");
        e("Quanto do centro do analógico é ignorado", "How much of the stick's centre is ignored");
        e("15% — padrão", "15% — default");
        e("Sensibilidade dos analógicos", "Stick sensitivity");
        e("Maior: o personagem chega à velocidade máxima com menos inclinação", "Higher: the character reaches full speed with less tilt");
        e("133% — padrão", "133% — default");
        e("Os analógicos mudam na hora, também pelo jogo (Opções → android settings).", "The sticks change at once, from the game too (Options → android settings).");
        e("Restaurar botões padrão", "Restore default buttons");
        e("O layout do controle volta ao do DualShock 2", "The controller layout goes back to the DualShock 2's");
        e("Xis (✕)", "Cross (✕)");
        e("Círculo (○)", "Circle (○)");
        e("Quadrado (□)", "Square (□)");
        e("Triângulo (△)", "Triangle (△)");
        e("L3 (analógico esquerdo)", "L3 (left stick)");
        e("R3 (analógico direito)", "R3 (right stick)");
        e("Direcional ↑", "D-pad ↑");
        e("Direcional ↓", "D-pad ↓");
        e("Direcional ←", "D-pad ←");
        e("Direcional →", "D-pad →");
        e("Aperte agora o botão do controle para ", "Press the controller button for ");
        e(".\n(Atual: ", ".\n(Now: ");
        e(". Cancela sozinho em 6 segundos.)", ". Cancels by itself in 6 seconds.)");
        e("Restaurar botões padrão?", "Restore the default buttons?");
        e("Todos os botões voltam ao layout do DualShock 2 (A = ✕, B = ○, X = □, Y = △...).",
          "Every button goes back to the DualShock 2 layout (A = ✕, B = ○, X = □, Y = △...).");

        // system
        e("Restaurar configurações padrão", "Restore default settings");
        e("Gráficos, áudio e controles voltam aos valores recomendados", "Graphics, audio and controls go back to the recommended values");
        e("Versão ", "Version ");
        e("Idioma / Language", "Language / Idioma");
        e("Automático (idioma do sistema)", "Automatic (the system's language)");
        e("O launcher muda na hora; as opções do port dentro do jogo ficam em inglês (a fonte do jogo).",
          "The launcher changes at once; the port's options inside the game stay in English (the game's font).");
        e("Restaurar configurações padrão?", "Restore the default settings?");
        e("Antisserrilhado (FXAA)", "Anti-aliasing (FXAA)");
        e("Suaviza bordas serrilhadas na imagem final; pode suavizar um pouco textos e o HUD",
          "Smooths jagged edges in the final picture; may soften text and the HUD slightly");
        e("FXAA", "FXAA");
        e("Dithering", "Dithering");
        e("Original: a saída de 16 bits com dithering do próprio PS2. Desligado: a imagem de 24 bits do port, mais lisa",
          "Original: the PS2's own dithered 16-bit output. Off: the port's smoother 24-bit picture");
        e("Original (PS2)", "Original (PS2)");
        e("Predefinição gráfica", "Graphics preset");
        e("Original: como no PS2 (resolução 1x). Equilibrado: resolução 2x, anisotrópica 4x, LOD 2x. Qualidade: resolução 4x, anisotrópica 16x, LOD 3x.",
          "Original: as on the PS2 (1x resolution). Balanced: 2x resolution, 4x anisotropic, 2x LOD. Quality: 4x resolution, 16x anisotropic, 3x LOD.");
        e("Equilibrado", "Balanced");
        e("Restaurar apenas gráficos", "Restore graphics only");
        e("Para tela preta ou falha ao iniciar: só as opções gráficas voltam ao padrão (áudio, controles, idioma e saves ficam)",
          "For a black screen or a failed start: only the graphic options go back to their defaults (audio, controls, language and saves stay)");
        e("Restaurar apenas gráficos?", "Restore the graphics only?");
        e("Filtro, resolução, proporção, FPS, VSync, anisotrópica, FXAA, dithering, LOD e contador voltam ao padrão.",
          "Filter, resolution, aspect, FPS, VSync, anisotropic, FXAA, dithering, LOD and counter go back to their defaults.");
        e("Gráficos restaurados", "Graphics restored");
        e("Qualidade", "Quality");
        e("Personalizado", "Custom");
        e("Restaurar", "Restore");
        e("Configurações padrão restauradas", "Default settings restored");
        e("Falha ao salvar em ", "Could not save to ");
        e("Não foi possível salvar em ", "Could not save to ");

        // diagnostics
        e("Compartilhar relatório", "Share report");
        e("desligado", "off");
        e("Compartilhar envia um arquivo de texto; copiar põe o mesmo texto na área de transferência.",
          "Share sends a text file; copy puts the same text on the clipboard.");
        e("Compartilhar", "Share");
        e("Copiar", "Copy");
        e("Gerando relatório…", "Making the report…");
        e("Relatório copiado (", "Report copied (");
        e(" caracteres)", " characters)");

        // the tools tab and the redesigned layout
        e("Ferramentas", "Tools");
        e("Abrir", "Open");
        e("Próximo valor", "Next value");
        e("Categoria", "Category");
        e("Voltar", "Back");
        e("Alterar", "Change");
        e("Port nativo para Android · versão ", "Native Android port · version ");
        e("Inicia o jogo com a imagem selecionada. No jogo, Menu principal → quit game volta para cá.", "Starts the game with the selected image. In the game, Main menu → quit game comes back here.");
        e("A imagem do disco é sua: o app copia a ISO ou o CHD para o armazenamento dele, sem pedir permissões. Nenhum dado do jogo vem no aplicativo.", "The disc image is yours: the app copies the ISO or CHD into its own storage, without asking for permissions. No game data ships with the app.");
        e("Idioma, restauração e informações do aplicativo.", "Language, restoring and app information.");
        e("Ferramentas para investigar problemas e acompanhar o port entre versões. Tudo roda só quando você pede, nunca durante o jogo. Relatórios e o ZIP não levam saves, cartões de memória nem a imagem do disco.", "Tools to investigate problems and follow the port across versions. Everything runs only when you ask, never during play. Reports and the ZIP never include saves, memory cards or the disc image.");
        e("Os saves do cartão de memória do jogo. Backups automáticos (antes de importar ou apagar): ", "The game's memory card saves. Automatic backups (before importing or deleting): ");
        e("A apaga este save (um backup automático é feito antes).\n\n", "A deletes this save (an automatic backup is made first).\n\n");
        e("Aperte A e depois o botão do controle que deve fazer o papel deste. Se ele já estiver em uso, os dois trocam de lugar. Vale no próximo início do jogo; o launcher não muda.", "Press A, then the controller button that should play this one's part. If it is already in use, the two swap. Applies from the next game start; the launcher does not change.");
        e("ÚLTIMA EXECUÇÃO", "LAST RUN");
        e("RELATÓRIOS", "REPORTS");
        e("ANÁLISE", "ANALYSIS");
        e("TESTES", "TESTS");
        e("OPÇÕES", "OPTIONS");
        e("Relatório completo", "Full report");
        e("Aparelho, renderizador, áudio, controles, configurações, última execução, histórico e logs num só texto, pronto para colar numa IA. Compartilhar ou copiar.", "Device, renderer, audio, controls, settings, last run, history and logs in one text, ready to paste into an AI. Share or copy.");
        e("Exportar pacote ZIP", "Export ZIP package");
        e("Todos os registros técnicos (logs do jogo e do launcher, travamentos, histórico de sessões, verificações, aparelho e ps2r.ini) num .zip onde você escolher. Sem saves nem a ISO.", "All technical records (game and launcher logs, crashes, session history, checks, device and ps2r.ini) in a .zip wherever you choose. No saves, no ISO.");
        e("Desempenho", "Performance");
        e("FPS, tempo e ritmo dos quadros, GPU, CPU, memória, temperatura, consumo e tela da última execução: mínimo, média e máximo, com as configurações usadas.", "FPS, frame time and pacing, GPU, CPU, memory, temperature, power and display of the last run: minimum, average and maximum, with the settings used.");
        e("RESUMO (mín / média / máx)", "SUMMARY (min / avg / max)");
        e("Tempo de quadro", "Frame time");
        e("Frame pacing", "Frame pacing");
        e("Quadros atrasados", "Late frames");
        e("acima de 1,5× a mediana ·", "over 1.5× the median ·");
        e("acima de 2,5×", "over 2.5×");
        e("GPU por quadro", "GPU per frame");
        e("indisponível neste aparelho", "not available on this device");
        e("CPU (um núcleo)", "CPU (one core)");
        e("Memória máxima", "Peak memory");
        e("Temperatura bateria", "Battery temperature");
        e("Corrente", "Current");
        e("Consumo estimado", "Estimated power");
        e("não estimado (no carregador)", "not estimated (on the charger)");
        e("indisponível", "not available");
        e("Estado térmico (pior)", "Thermal status (worst)");
        e("folga térmica máx. ", "max thermal headroom ");
        e("Atualização da tela", "Display refresh");
        e("CONFIGURAÇÕES GRÁFICAS (janelas de ~10 s)", "GRAPHICS SETTINGS (~10 s windows)");
        e("JANELAS", "WINDOWS");
        e("eng.", "hitch.");
        e("Modo de tela pedido", "Display mode asked");
        e("Engasgos (> 50 ms)", "Hitches (> 50 ms)");
        e("ENGASGOS (quadros acima de 50 ms)", "HITCHES (frames over 50 ms)");
        e("tela de carregamento (esperado)", "loading picture (expected)");
        e("área", "area");
        e("causa", "cause");
        e("tela de carregamento", "loading picture");
        e("compilação de shaders", "shader compilation");
        e("espera pela geometria", "waiting for geometry");
        e("renderização (envio / troca de quadro)", "rendering (submit / swap)");
        e("jogo: troca de área", "game: area change");
        e("jogo (CPU da thread do jogo)", "game (the game thread's CPU)");
        e("indefinido", "undetermined");
        e("ENERGIA E TEMPERATURA (a cada 10 s)", "POWER AND TEMPERATURE (every 10 s)");
        e("bateria", "battery");
        e("carregador", "charger");
        e(" °C máx.", " °C max");
        e("nenhum", "none");
        e("leve", "light");
        e("moderado", "moderate");
        e("severo", "severe");
        e("crítico", "critical");
        e("emergência", "emergency");
        e("desligamento", "shutdown");
        e("Falhas e travamentos", "Failures and crashes");
        e("Como terminou a última execução e as linhas de erro, travamento e encerramento dos logs.", "How the last run ended and the logs' error, crash and exit lines.");
        e("Histórico de sessões", "Session history");
        e("As últimas ", "The last ");
        e(" sessões com versão, duração, FPS, ritmo dos quadros, GPU, temperatura, consumo e configurações: para comparar versões e configurações.", " sessions with version, length, FPS, frame pacing, GPU, temperature, power and settings: to compare versions and settings.");
        e("Verificar componentes", "Check components");
        e("Armazenamento, configurações, imagem do disco, biblioteca nativa, gráficos, áudio, controles, vibração e memória, em poucos segundos.", "Storage, settings, disc image, native library, graphics, audio, controls, vibration and memory, in a few seconds.");
        e("Teste do ambiente", "Environment test");
        e("Carrega as bibliotecas nativas e testa fibers, armazenamento, imagem do disco e OpenGL ES, sem o jogo.", "Loads the native libraries and tests fibers, storage, the disc image and OpenGL ES, without the game.");
        e("Apagar registros técnicos", "Delete technical records");
        e("Apagar registros técnicos?", "Delete technical records?");
        e("Apaga os logs, relatórios e verificações (o histórico de sessões, os saves e as configurações ficam).", "Deletes the logs, reports and checks (the session history, saves and settings stay).");
        e("Os logs, relatórios e verificações são apagados. O histórico de sessões, os saves, as configurações e a ISO ficam.", "The logs, reports and checks are deleted. The session history, saves, settings and the ISO stay.");
        e("Registros apagados: ", "Records deleted: ");
        e("Fechar", "Close");
        e("Nenhuma medida ainda: jogue pelo menos 10 segundos e volte aqui.", "No measurements yet: play at least 10 seconds and come back.");
        e("Média ", "Average ");
        e("mínima ", "lowest ");
        e("quadro ", "frame ");
        e("pior engasgo ", "worst hitch ");
        e("% de um núcleo · memória máxima ", "% of one core · peak memory ");
        e("% de um núcleo  ·  memória ", "% of one core  ·  memory ");
        e("Desempenho da última execução", "Last run's performance");
        e("Última execução: ", "Last run: ");
        e("Última execução", "Last run");
        e("Execução anterior", "Previous run");
        e("nenhuma linha de erro", "no error line");
        e("Funções no travamento (libmain.so):", "Functions at the crash (libmain.so):");
        e("Nenhuma sessão registrada ainda: as sessões entram aqui depois de jogar.", "No session recorded yet: sessions appear here after playing.");
        e("início", "start");
        e("versão", "version");
        e("duração", "length");
        e("fim", "end");
        e("config.", "settings");
        e("Verificando…", "Checking…");
        e("Verificação dos componentes", "Component check");
        e("Gerando o pacote…", "Making the package…");
        e("Pacote exportado: ", "Package exported: ");
        e(" (mín. ", " (min. ");
        e("pior ", "worst ");
        e("Sem medidas de desempenho (menos de 10 s de jogo).", "No performance figures (under 10 s of play).");
        e(" linha(s) de erro fatal — veja Falhas e travamentos.", " fatal error line(s) — see Failures and crashes.");
        e("A última execução terminou de forma inesperada. Veja Ferramentas → Falhas e travamentos.", "The last run ended unexpectedly. See Tools → Failures and crashes.");
        e("A última execução travou. Veja Ferramentas → Falhas e travamentos.", "The last run crashed. See Tools → Failures and crashes.");
        e("Nenhuma execução registrada", "No run recorded");
        e("Terminou normalmente", "Ended normally");
        e("Travou", "Crashed");
        e("Encerrada pelo programa", "Ended by the program");
        e("Interrompida (ou em execução)", "Interrupted (or running)");
        e("Desconhecido", "Unknown");

        e("Distância de detalhe (LOD)", "Detail distance (LOD)");

        e("16:9 liga o widescreen do jogo (imagem mais larga, sem esticar); 4:3 é a imagem original. O jogo acompanha sozinho",
          "16:9 turns the game's widescreen on (a wider picture, not stretched); 4:3 is the original picture. The game follows by itself");
        e("Proporção 16:9, VSync ligado, anisotrópica, FXAA e dithering desligados, resolução 1x, 60 FPS, contador desligado, LOD original, ",
          "16:9 shape, VSync on, anisotropic, FXAA and dithering off, 1x resolution, 60 FPS, counter off, original LOD, ");
        e("analógicos no padrão. A ISO e os saves não mudam.", "sticks at their defaults. The ISO and the saves do not change.");
        e("O ícone personalizado foi removido", "The custom icon was removed");

        // exit
        e("Sair do aplicativo?", "Exit the application?");
        e("O jogo ainda está aberto em segundo plano. Saia pelo jogo (Menu principal → quit game) "
                + "para não perder progresso não salvo. Sair mesmo assim encerra o jogo.",
          "The game is still open in the background. Leave it from the game (Main menu → quit game) "
                + "so no unsaved progress is lost. Exiting anyway ends the game.");
        e("O Twinsanity Pocket será fechado. Configurações, saves e a ISO continuam guardados.",
          "Twinsanity Pocket will close. Settings, saves and the ISO stay stored.");
        e("Sair", "Exit");
        e("Cancelar", "Cancel");
        e("OK", "OK");

        // the disc image
        e("Aguarde a cópia da ISO terminar.", "Wait for the ISO copy to finish.");
        e("Imagem do disco não disponível", "Disc image not available");
        e("O jogo precisa da SUA imagem do Crash Twinsanity (PAL SLES-52568 ou NTSC-U SLUS-20909 v2.00).\n\n",
          "The game needs YOUR Crash Twinsanity disc image (PAL SLES-52568 or NTSC-U SLUS-20909 v2.00).\n\n");
        e("Escolha \"Selecionar\" e indique o arquivo no gerenciador de arquivos do Android.\n\n",
          "Choose \"Select\" and point to the file in Android's file manager.\n\n");
        e("Detalhe: ", "Detail: ");
        e("Iniciar mesmo assim", "Start anyway");
        e("O seletor de arquivos do Android não abriu: ", "Android's file picker did not open: ");
        e("Espaço insuficiente", "Not enough space");
        e("A ISO tem ", "The ISO is ");
        e(" e o armazenamento interno tem ", " and the internal storage has ");
        e(" livres. Libere espaço e tente de novo.", " free. Free some space and try again.");
        e("Copiando ", "Copying ");
        e("Copiando a ISO para o app", "Copying the ISO into the app");
        e(" de ", " of ");
        e("ISO pronta", "ISO ready");
        e(" foi copiada e verificada:\n", " was copied and checked:\n");
        e("\n\nVocê pode apagar o arquivo original se quiser liberar espaço.", "\n\nYou can delete the original file to free space.");
        e("Iniciar jogo", "Start game");
        e("A cópia da ISO falhou", "The ISO copy failed");
    }
}

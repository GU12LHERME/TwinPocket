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
        e("Áudio", "Audio");
        e("Controles", "Controls");
        e("Sistema", "System");
        e("JOGAR", "PLAY");
        e("Iniciando…", "Starting…");
        e("Selecionar ISO", "Select ISO");
        e("Sair do aplicativo", "Exit application");
        e("JOGO SELECIONADO", "SELECTED GAME");
        e("TwinsanityEvolved · port nativo Android", "TwinsanityEvolved · native Android port");
        e("A  Confirmar      B  Voltar      LB / RB  Abas      ◀ ▶  Alterar      START  Jogar",
          "A  Confirm      B  Back      LB / RB  Tabs      ◀ ▶  Change      START  Play");
        e("Jogar", "Play");
        e("Selecionar ISO ou CHD…", "Select ISO or CHD…");
        e("Copia a SUA imagem do jogo para o app (nenhuma permissão de armazenamento)", "Copies YOUR game image into the app (no storage permission)");
        e("No jogo: Opções → android settings → return to launcher volta para cá.", "In game: Options → android settings → return to launcher comes back here.");
        e("RESTAURAR", "RESTORE");
        e("Saves", "Saves");
        e("Os saves do cartão de memória do jogo: backup, restauração e exclusão.", "The game's memory card saves: backup, restore and deletion.");
        e("BACKUP", "BACKUP");
        e("SAVES NO CARTÃO", "SAVES ON THE CARD");
        e("Exportar backup", "Export backup");
        e("Salva todos os cartões de memória num arquivo .zip onde você escolher", "Saves every memory card to a .zip file wherever you choose");
        e("Importar backup", "Import backup");
        e("Restaura um .zip exportado (os cartões atuais ganham um backup automático antes)", "Restores an exported .zip (the current cards are backed up automatically first)");
        e("Nenhum save ainda. Os saves aparecem aqui depois de salvar dentro do jogo.", "No saves yet. Saves show up here after saving in the game.");
        e("cartão principal", "main card");
        e("cartão dos mods", "mods card");
        e("Backups automáticos (antes de importar ou apagar): ", "Automatic backups (before importing or deleting): ");
        e("Nenhum gerenciador de arquivos disponível.", "No file manager available.");
        e("O jogo está aberto", "The game is open");
        e("Saia do jogo (Opções → android settings → return to launcher) antes de mexer nos saves: o jogo grava no cartão de memória.",
          "Leave the game (Options → android settings → return to launcher) before touching the saves: the game writes the memory card.");
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
        e("SAÍDA DE ÁUDIO", "AUDIO OUTPUT");
        e("ANALÓGICOS", "STICKS");
        e("VIBRAÇÃO", "VIBRATION");
        e("IDIOMA", "LANGUAGE");
        e("Idioma, restauração, diagnóstico e informações do aplicativo.", "Language, restoring, diagnostics and the app's information.");
        e("DIAGNÓSTICO", "DIAGNOSTICS");
        e("APLICATIVO", "APPLICATION");
        e("BOTÕES", "BUTTONS");
        e("Modo diagnóstico", "Diagnostic mode");
        e("Registra as mensagens da GPU, os analógicos e a vibração do jogo e permite capturar um quadro com L3 + R3 (deixe desligado para jogar)",
          "Logs the GPU messages, the sticks and the game's vibration and lets L3 + R3 capture a frame (leave off to play)");
        e("Fecha o TwinsanityEvolved (configurações, saves e a ISO ficam guardados)", "Closes TwinsanityEvolved (settings, saves and the ISO are kept)");
        e("versão ", "version ");
        e("Nenhuma ISO selecionada", "No ISO selected");
        e("Escolha \"Selecionar ISO ou CHD…\" e indique a SUA imagem do Crash Twinsanity (PAL ou NTSC-U).",
          "Choose \"Select ISO or CHD…\" and point to YOUR Crash Twinsanity disc image (PAL or NTSC-U).");
        e("Pronta — ", "Ready — ");
        e("Não utilizável: ", "Not usable: ");
        e("Ainda não executado nesta instalação.", "Not run yet on this installation.");
        e("O jogo está em execução.", "The game is running.");
        e("A última execução terminou de forma inesperada. Veja Sistema → Compartilhar relatório.",
          "The last run ended unexpectedly. See System → Share report.");
        e("A última execução travou. Veja Sistema → Compartilhar relatório.", "The last run crashed. See System → Share report.");
        e("A última execução terminou normalmente.", "The last run ended normally.");

        // graphics
        e("Filtro de texturas", "Texture filter");
        e("Bilinear corrige os brilhos quadrados (máscara do Aku Aku, cogumelos, cachoeira)",
          "Bilinear fixes the square glows (Aku Aku's mask, the mushrooms, the waterfall)");
        e("Original (do jogo)", "Original (the game's)");
        e("Point (pixelado)", "Point (pixelated)");
        e("Bilinear — recomendado", "Bilinear — recommended");
        e("Filtragem anisotrópica", "Anisotropic filtering");
        e("Texturas do chão e paredes mais nítidas em ângulo; limitada ao máximo do driver (o log diz o aplicado)",
          "Sharper floor and wall textures at an angle; limited to the driver's maximum (the log says what was applied)");
        e("Desligada — padrão", "Off — default");
        e("Resolução interna", "Internal resolution");
        e("Mais alta: imagem mais nítida e mais trabalho para a GPU", "Higher: a sharper picture and more work for the GPU");
        e("1x (nativa do PS2)", "1x (the PS2's own)");
        e("Proporção da tela", "Aspect ratio");
        e("Widescreen com a opção widescreen do jogo ligada (Opções → Opções gráficas, fica no save) é o widescreen verdadeiro; desligada, a imagem 4:3 fica esticada",
          "Widescreen with the game's own widescreen option on (Options → graphic options, kept in the save) is true widescreen; with it off, the 4:3 picture is stretched");
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
        e("Distância de detalhe (LOD, experimental)", "Detail distance (LOD, experimental)");
        e("Mantém os modelos detalhados por mais distância, só entre os que o jogo já carregou. Exige mais da GPU",
          "Keeps the detailed models farther away, only among what the game has loaded. More work for the GPU");
        e("Original (1x)", "Original (1x)");
        e("Média (2x)", "Medium (2x)");
        e("Longa (3x)", "Long (3x)");
        e("Máxima (4x)", "Max (4x)");
        e("As mesmas opções ficam no jogo: Opções → Opções gráficas.", "The same options are in the game: Options → graphic options.");

        // audio
        e("Driver de áudio", "Audio driver");
        e("OpenSL ES é o que funcionou no Retroid Pocket 6 (vale no próximo início)", "OpenSL ES is the one that worked on the Retroid Pocket 6 (applies at the next start)");
        e("OpenSL ES — recomendado", "OpenSL ES — recommended");
        e("Automático (ordem do SDL)", "Automatic (SDL's order)");
        e("Tamanho do buffer", "Buffer size");
        e("Maior: mais estável, com um pouco mais de atraso (vale no próximo início)", "Larger: steadier, with a little more latency (applies at the next start)");
        e("2048 (43 ms) — padrão", "2048 (43 ms) — default");
        e("O volume dos efeitos e da música fica no menu do jogo (Opções → Opções de som). ",
          "The effects' and the music's volumes are in the game's menu (Options → sound options). ");
        e("Driver e buffer também estão no jogo: Opções → android settings.", "The driver and the buffer are in the game too: Options → android settings.");

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
        e("Escolha um botão do PS2 e aperte o botão do controle que deve fazer o papel dele. Se o botão "
                + "já estiver em uso, os dois trocam de lugar. Vale no próximo início do jogo; o launcher não muda.",
          "Choose a PS2 button and press the controller button that should act as it. If that button is already in use, "
                + "the two swap. Applies at the next game start; the launcher does not change.");
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
        e("Arquivo de configurações (compartilhado com o jogo):\n", "Settings file (shared with the game):\n");
        e("Versão ", "Version ");
        e("Idioma / Language", "Language / Idioma");
        e("Automático (idioma do sistema)", "Automatic (the system's language)");
        e("O launcher muda na hora; as opções do port dentro do jogo ficam em inglês (a fonte do jogo).",
          "The launcher changes at once; the port's options inside the game stay in English (the game's font).");
        e("Restaurar configurações padrão?", "Restore the default settings?");
        e("Widescreen 16:9, VSync ligado, filtro bilinear, anisotrópica, FXAA e dithering desligados, resolução 1x, 60 FPS, contador desligado, LOD original, OpenSL ES com ",
          "Widescreen 16:9, VSync on, bilinear filter, anisotropic, FXAA and dithering off, resolution 1x, 60 FPS, counter off, LOD original, OpenSL ES with ");
        e("Antisserrilhado (FXAA)", "Anti-aliasing (FXAA)");
        e("Suaviza bordas serrilhadas na imagem final; pode suavizar um pouco textos e o HUD",
          "Smooths jagged edges in the final picture; may soften text and the HUD slightly");
        e("FXAA", "FXAA");
        e("Dithering", "Dithering");
        e("Original: a saída de 16 bits com dithering do próprio PS2. Desligado: a imagem de 24 bits do port, mais lisa",
          "Original: the PS2's own dithered 16-bit output. Off: the port's smoother 24-bit picture");
        e("Original (PS2)", "Original (PS2)");
        e("Predefinição gráfica", "Graphics preset");
        e("Original: como o jogo. Equilibrado: anisotrópica 4x, LOD 2x. Qualidade: anisotrópica 16x, LOD 4x. A resolução não muda (2x ainda não validado)",
          "Original: as the game. Balanced: anisotropic 4x, LOD 2x. Quality: anisotropic 16x, LOD 4x. The resolution does not change (2x not validated yet)");
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
        e("buffer 2048, analógicos no padrão. A ISO e os saves não mudam; a opção widescreen do jogo fica no save (Opções → Opções gráficas).",
          "buffer 2048, sticks at their defaults. The ISO and the saves do not change; the game's widescreen option is kept in the save (Options → graphic options).");
        e("Restaurar", "Restore");
        e("Configurações padrão restauradas", "Default settings restored");
        e("Falha ao salvar em ", "Could not save to ");
        e("Não foi possível salvar em ", "Could not save to ");

        // diagnostics
        e("Compartilhar relatório", "Share report");
        e("Um arquivo de texto com os logs da última execução", "A text file with the last run's logs");
        e("Copiar relatório", "Copy report");
        e("O mesmo relatório para a área de transferência", "The same report to the clipboard");
        e("desligado", "off");
        e("Registra as mensagens da GPU e os analógicos e permite capturar um quadro com L3 + R3 (deixe desligado para jogar)",
          "Logs the GPU's messages and the sticks and lets L3 + R3 capture a frame (leave it off to play)");
        e("Diagnóstico sem o jogo", "Diagnostics without the game");
        e("Carrega as bibliotecas nativas e testa o ambiente", "Loads the native libraries and tests the environment");
        e("Teste do SDL sem o jogo", "SDL test without the game");
        e("Abre a janela, o controle e o áudio sem a ISO", "Opens the window, the controller and the audio without the ISO");
        e("Gerando relatório…", "Making the report…");
        e("Relatório copiado (", "Report copied (");
        e(" caracteres)", " characters)");

        // exit
        e("Sair do aplicativo?", "Exit the application?");
        e("O jogo ainda está aberto em segundo plano. Saia pelo jogo (Opções → android settings → return to launcher) "
                + "para não perder progresso não salvo. Sair mesmo assim encerra o jogo.",
          "The game is still open in the background. Leave it from the game (Options → android settings → return to launcher) "
                + "so no unsaved progress is lost. Exiting anyway ends the game.");
        e("O TwinsanityEvolved será fechado. Configurações, saves e a ISO continuam guardados.",
          "TwinsanityEvolved will close. Settings, saves and the ISO stay stored.");
        e("Sair", "Exit");
        e("Cancelar", "Cancel");
        e("OK", "OK");

        // the disc image
        e("Aguarde a cópia da ISO terminar.", "Wait for the ISO copy to finish.");
        e("Imagem do disco não disponível", "Disc image not available");
        e("O jogo precisa da SUA imagem do Crash Twinsanity (PAL SLES-52568 ou NTSC-U SLUS-20909 v2.00).\n\n",
          "The game needs YOUR Crash Twinsanity disc image (PAL SLES-52568 or NTSC-U SLUS-20909 v2.00).\n\n");
        e("Escolha \"Selecionar ISO ou CHD…\" e indique o arquivo no gerenciador de arquivos do Android.\n\n",
          "Choose \"Select ISO or CHD…\" and point to the file in Android's file manager.\n\n");
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

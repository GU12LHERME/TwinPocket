[English](README.md) | **Português (Brasil)**

# TwinPocket

**Port nativo de Crash Twinsanity (PS2) para Android ARM64.** Não é um emulador: o código do jogo é compilado para o
aparelho e roda direto nele, a partir da **sua** cópia do disco. Feito e testado no **Retroid Pocket 6**.

> Nenhum dado do jogo está neste repositório nem no aplicativo. Você precisa da sua própria imagem do disco (ISO ou CHD).

## Download

Baixe o APK ARM64 da [última versão](https://github.com/GU12LHERME/TwinsanityPocket/releases/latest) e instale.
As atualizações instalam por cima da anterior (desde a 1.5.0), mantendo saves, configurações e a imagem do jogo.

| | |
|---|---|
| Requisitos | Android 7.0+, processador ARM64 (arm64-v8a), OpenGL ES 3.2 |
| Jogo | a sua imagem do Crash Twinsanity, ISO ou CHD (versão USA testada) |
| Pacote | `com.twinpocket.android` |
| Assinatura | SHA-256 do certificado `0b8284df91fca27a6fa616bb1aefe197954d628859339f9e0afe7fcb21820fac` |

[GitHub Actions](https://github.com/GU12LHERME/TwinsanityPocket/actions) · [Notas de atualização](android/game/NOTAS-DA-VERSAO.txt) · [Como contribuir](CONTRIBUTING.md)

## Como usar

1. Abra o app e, na aba **Jogar**, toque em **Selecionar** e escolha a sua ISO ou CHD (o app faz uma cópia para o
   armazenamento dele; nenhuma permissão de armazenamento é pedida).
2. Aperte **JOGAR**. No jogo, *Menu principal → quit game* volta para o launcher.

O launcher é todo navegável por controle (XInput) ou toque e tem as abas **Jogar**, **Gráficos**, **Controles**,
**Saves**, **Sistema** e **Ferramentas** (relatório de desempenho, falhas, histórico de sessões e exportação de um ZIP
de diagnóstico, sem saves nem dados do jogo).

## Desempenho (Retroid Pocket 6)

Medido no aparelho com os relatórios do próprio app (Snapdragon 8 Gen 2, Adreno 740, Android 13):

- **60 FPS estáveis** em resolução interna 3x (1536×1344), anisotrópica 16x e distância de detalhe longa
- GPU ~2,3 ms por quadro (de 16,7 ms), CPU ~1,2 núcleo, memória ~560 MB
- bateria em ~30–33 °C, consumo ~3,9 W nas sessões de jogo medidas (estado térmico "nenhum")
- áudio sem falhas, inclusive ao minimizar e voltar; sem engasgos ao entrar nas fases (ISO ou CHD)

Outros aparelhos (principalmente com GPU Mali ou PowerVR) e a versão PAL não foram testados.

## Gráficos

- Resolução interna 1x (512×448, a do PS2), 2x (1024×896) ou 3x (1536×1344)
- Proporção 4:3 ou 16:9 (o widescreen do próprio jogo, sem esticar), 30 ou 60 FPS, VSync
- Filtragem anisotrópica, FXAA e distância de detalhe (LOD) até 3x
- Predefinições Original / Equilibrado / Qualidade; dithering do PS2 sempre ligado

## Limitações conhecidas

- Uma pausa de ~2 s ao abrir o jogo, antes da tela título (inofensiva)
- As cutscenes em vídeo usam mais CPU do que o ideal
- O aquecimento inesperado relatado na tela de Start ainda precisa de validação específica; as medições em jogo não comprovam o comportamento do menu.

## Como é feito

O jogo é a decompilação [twinsanity-reversed](https://github.com/Smartkin/twinsanity-reversed) (`game/`), compilada
para wasm32 e convertida em C com wasm2c, sobre o motor NativeEE (`engine/`) com OpenGL ES 3.2, OpenSL ES e SDL2.
O NDK compila tudo em `libmain.so`; o launcher (`android/game`) é Java puro.

| Pasta | O que é |
|---|---|
| `android/game/` | o aplicativo Android: launcher, ferramentas, build do APK ([README](android/game/README.md), [notas da versão](android/game/NOTAS-DA-VERSAO.txt)) |
| `runner/te_android*.cpp` | a entrada do jogo no Android e os diagnósticos nativos |
| `platform/nativeee/host/` | a ponte entre o jogo e o motor (gráficos, áudio, disco, configurações) |
| `engine/` | o motor NativeEE |
| `game/` | a decompilação do jogo |
| `.github/workflows/android-game-arm64.yml` | o build do APK, os testes e a publicação das versões |

O APK é gerado pelo GitHub Actions. A publicação de novas versões ocorre por tag numérica correspondente à versão do APK. Consulte [android/game/README.md](android/game/README.md) para compilar localmente.

## Créditos e licenças

TwinPocket é um fork de [TwinsanityEvolved](https://github.com/menaman123/TwinsanityEvolved), o port para PC e
PS Vita, cujo README original está em [docs/TwinsanityEvolved-README.md](docs/TwinsanityEvolved-README.md).

- TwinsanityEvolved e NativeEE — MIT ([LICENSE](LICENSE))
- twinsanity-reversed — a decompilação do jogo
- SDL2 — zlib · libchdr — BSD 3-Clause
- FFmpeg 6.1.2 — LGPL v2.1 ou posterior, compilado sem partes GPL ou nonfree ([FFmpeg-LICENSE.md](FFmpeg-LICENSE.md),
  receita em `tools/android/ffmpeg_android.sh`)
- Avisos de terceiros: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)

Crash Twinsanity é marca de seus respectivos donos. Este projeto não é afiliado a eles e não distribui nenhum dado do
jogo.

---

*English: TwinPocket is a native Android ARM64 port of Crash Twinsanity (PS2), not an emulator, built from the
twinsanity-reversed decompilation on the NativeEE engine and tested on the Retroid Pocket 6. Bring your own disc image
(ISO or CHD); no game data is included. Download the APK from the [releases](https://github.com/GU12LHERME/TwinsanityPocket/releases/latest).*

## Publicação de versões

Atualize a versão do aplicativo e as notas de atualização antes de criar uma tag numérica correspondente (por exemplo, `2.0.0`). O GitHub Actions gera o APK ARM64 assinado e publica a release com `SHA256SUMS.txt`. Releases existentes não são substituídas. Builds comuns ficam nos artefatos do Actions.


## Ajuda e segurança

[FAQ](docs/FAQ.md) · [Troubleshooting / Solução de problemas](docs/TROUBLESHOOTING.md) · [Security / Segurança](SECURITY.md)

**Migração da versão 1.8.0 ou anterior:** Exporte os saves antes de instalar a 1.8.1 e importe-os no TwinPocket. O novo pacote Android é um aplicativo separado; selecione novamente a imagem do jogo e as configurações.

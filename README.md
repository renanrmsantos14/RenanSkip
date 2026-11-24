# RenanSkip

App Android mínimo (Java puro, sem dependências) para trocar de música segurando o botão de volume com o celular bloqueado. Feito para o Poco X5 Pro (HyperOS/MIUI), funciona em Android 8+.

- Segurar **volume +** → próxima faixa
- Segurar **volume −** → faixa anterior
- Toque rápido → só muda o volume, como sempre

## Como funciona
Com a tela desligada o Android não entrega a tecla de volume a apps comuns (sem root/ADB). Mas o volume de mídia continua mudando, e segurar a tecla gera passos em sequência rápida (auto-repetição). Um serviço em primeiro plano escuta `VOLUME_CHANGED_ACTION`, detecta a sequência, envia `MEDIA_NEXT`/`MEDIA_PREVIOUS` ao player ativo, vibra e devolve o volume ao valor anterior.

Limite: no volume máximo, segurar + não muda o volume, então não dispara.

## Build e instalação
```bash
JAVA_HOME=~/dev/jdk17 ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
`local.properties` com `sdk.dir` é local (não versionado).

## Releases e atualização automática
Cada push na `main` (exceto só `.md`) roda `.github/workflows/apk.yml`: versão `0.0.<run_number>`, APK assinado com a chave fixa (secrets `KEYSTORE_BASE64` e `KEYSTORE_PASSWORD`) e publicado no GitHub Releases com `.apk.sha256`.
O app procura versão nova ao abrir (botão "Atualizar para vX") e o serviço confere a cada 6 h e notifica. Baixa, confere o SHA-256 e abre o instalador do Android.

Chave local: `~/.android-keys/renanskip-release.keystore` (+ `.password`). Sem ela, quem já instalou não recebe mais atualização. O APK debug local tem outra assinatura: desinstale antes de instalar o da Release.

## No celular (HyperOS/MIUI)
1. Abra o app, ative e permita notificações.
2. "Liberar bateria" → Sem restrições.
3. Configurações do app → Autostart ligado.

Ajuste fino de sensibilidade: constantes `REPEAT_MS` e `REPEATS_TO_FIRE` em `SkipService.java`.

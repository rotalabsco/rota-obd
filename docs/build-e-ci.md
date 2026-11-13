# Build e CI

## Requisitos

- JDK 17
- Gradle 8.11 ou mais novo (o repositório não traz o wrapper; o Android Studio usa o próprio)
- Android SDK com a plataforma 35

Versões: Android Gradle Plugin 8.7.3, Kotlin 2.1.0, Compose BOM 2024.12.01, minSdk 26, targetSdk 34.

## Comandos

```bash
gradle testDebugUnitTest   # testes do core (JVM, sem aparelho)
gradle assembleDebug       # gera app/build/outputs/apk/debug/app-debug.apk
```

## APK automático pelo GitHub Actions

O workflow está em [`ci/build.yml`](ci/build.yml). Para ativá-lo, ele precisa estar em
`.github/workflows/build.yml`. Pelo site do GitHub: **Add file → Create new file**, nome
`.github/workflows/build.yml`, colar o conteúdo e fazer o commit. (Ferramentas de terceiros, inclusive
o conector do Claude, em geral não têm permissão para criar arquivos de workflow.)

O que ele faz a cada push na `main`:
1. Roda os testes e compila o APK de depuração.
2. Publica o APK em **Releases → Versão de teste** (`latest`), substituindo a anterior.
3. Se falhar, abre uma issue com os erros de compilação e o fim do log.

Depois de transferir o repositório para outra conta, nada precisa mudar: o workflow usa o token do
próprio repositório. Confira em Settings → Actions → General se o Actions está habilitado e se a
permissão de workflow está em "Read and write".

## Instalar na central

1. Baixar `rota-obd-debug.apk` em Releases.
2. Copiar por pendrive ou baixar direto na central e abrir com o gerenciador de arquivos.
3. Permitir instalação de fontes desconhecidas.

Para depurar: depuração sem fio do Android 14 (Opções do desenvolvedor → Depuração por Wi-Fi → parear
com código), com central e computador na mesma rede, e então `adb pair`, `adb connect`, `adb logcat`.
Nas centrais, a porta USB costuma ser só de entrada e não serve para adb.

## Prints das telas

Ainda não há prints do app rodando. As imagens geradas durante o desenvolvimento foram reproduções do
layout a partir do código, não capturas reais.

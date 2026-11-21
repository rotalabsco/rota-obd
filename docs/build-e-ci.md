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

O workflow está em [`.github/workflows/build.yml`](../.github/workflows/build.yml) e roda a cada push
na `main` e em cada pull request.

O que ele faz a cada push na `main`:
1. Roda os testes e compila o APK de depuração.
2. Publica o APK em **Releases → Versão de teste** (`latest`), substituindo a anterior.
3. Se falhar, abre uma issue com os erros de compilação e o fim do log.

Depois de transferir o repositório para outra conta, nada precisa mudar: o workflow usa o token do
próprio repositório. Confira em Settings → Actions → General se o Actions está habilitado e se a
permissão de workflow está em "Read and write".

### Chave de assinatura

O Android só instala uma versão nova por cima da antiga se as duas tiverem a mesma assinatura. Uma
máquina nova do GitHub Actions cria uma chave de depuração nova a cada build, então o workflow lê a
chave do segredo `DEBUG_KEYSTORE_B64` (o `debug.keystore` em base64). Para configurar:

```bash
base64 -w0 ~/.android/debug.keystore | gh secret set DEBUG_KEYSTORE_B64 -R rotalabsco/rota-obd
```

Trocar a chave obriga a desinstalar o app uma vez, o que apaga a Garagem. Usar o `debug.keystore` da
máquina de desenvolvimento deixa a assinatura igual à do build local, mas o Android também recusa
`versionCode` menor: um APK local (versionCode 1) só instala por cima de um do CI com
`GITHUB_RUN_NUMBER` maior definido no build, ou depois de desinstalar.

## Instalar na central

1. Baixar `rota-obd-debug.apk` em Releases.
2. Copiar por pendrive ou baixar direto na central e abrir com o gerenciador de arquivos.
3. Permitir instalação de fontes desconhecidas.

Para depurar: depuração sem fio do Android 14 (Opções do desenvolvedor → Depuração por Wi-Fi → parear
com código), com central e computador na mesma rede, e então `adb pair`, `adb connect`, `adb logcat`.
Nas centrais, a porta USB costuma ser só de entrada e não serve para adb.

## Ambiente local sem Android Studio

Dá para compilar e testar só com a linha de comando: JDK 17 (com `javac`; o JRE não basta), Gradle 8.11.1 e o
Android SDK com `platforms;android-35`, `build-tools;35.0.0` e `platform-tools`. Crie `local.properties` com
`sdk.dir=/caminho/do/Android/Sdk` (o arquivo fica fora do git).

Para ver as telas sem a central, um emulador em 1280×720 imita a ADAK LINK8S:

```bash
sdkmanager "emulator" "system-images;android-34;google_apis;x86_64"
avdmanager create avd -n central -k "system-images;android-34;google_apis;x86_64" -d pixel_c
# em ~/.android/avd/central.avd/config.ini: hw.lcd.width=1280, hw.lcd.height=720, hw.lcd.density=160
emulator -avd central -no-window -gpu swiftshader_indirect &
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Teste também com `adb shell wm density 240`: a densidade real da central ainda não foi medida e as telas
precisam caber nas duas.

## Prints das telas

As imagens em [`prints/`](prints/) foram capturadas no emulador (1280×720, densidade 160) com o modo
demonstração e dados de exemplo na Garagem. Ainda não há prints da central.

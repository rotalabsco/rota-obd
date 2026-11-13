# Referências

O que foi consultado para desenhar o OBD Livre e o que foi aproveitado de cada fonte. Nenhum código de
terceiros foi copiado: o projeto todo é escrito do zero e licenciado em MIT.

## Projetos de código aberto

### OBDLogger — NaleenB/obd-profile-logger
- Link: https://github.com/NaleenB/obd-profile-logger
- Licença: MIT. Plataforma: iOS e macOS (Swift).
- O que é: logger ELM327 que, além do OBD padrão, lê dados do fabricante pelo **serviço 21** da Mitsubishi
  numa Outlander 2.2 DI-D (4N14): filtro de partículas, EGR, rail, injetores.
- O que aproveitamos (conhecimento, não código):
  - Perfis de BLE dos adaptadores (FFF0/FFF1/FFF2 e Microchip) e a escolha do tipo de escrita pelas
    propriedades da característica.
  - Acumular a resposta até o prompt `>`; uma notificação BLE não é uma resposta.
  - Multiquadro ISO-TP no formato do ELM327 (linha de tamanho + `0:`, `1:`...).
  - Vários módulos respondem a um pedido broadcast: nunca juntar as respostas; endereçar por módulo.
  - Códigos negativos 11, 12, 31 e 33 e o que cada um indica.
  - Mitsubishi: CAN 11 bits / 500 kbps, motor em 7E0/7E8, serviço 21 com identificador de 1 byte,
    varredura 00–FF em cerca de 15 segundos, e as posições dos bytes variam por versão da ECU.
  - Princípios: só leitura, falha de leitura nunca vira zero, perfil só vale se a calibração bater.
- Atenção: o autor considera improvável que a Pajero 3.2 DI-D responda ao serviço 21, por ser de uma
  geração anterior de diagnóstico. A nossa Pajero 2013/14 mostrou o motor em CAN 500 kbps, então isso
  precisa ser confirmado no carro pela Descoberta.

### kotlin-obd-api — eltonvs/kotlin-obd-api
- Link: https://github.com/eltonvs/kotlin-obd-api
- Licença: Apache 2.0. Biblioteca Kotlin para comandos OBD-II.
- O que aproveitamos: referência de fórmulas de PIDs padrão e organização de comandos. Não usamos a
  biblioteca porque ela não cobre o serviço 21 nem o endereçamento por módulo de que precisamos.

### AndrOBD — fr3ts0n/AndrOBD
- Link: https://github.com/fr3ts0n/AndrOBD
- Licença: GPL-3.0. App Android de diagnóstico com plugins (inclusive publicação MQTT).
- O que aproveitamos: só referência de arquitetura e de ideias (plugins, exportação). Nenhum código,
  para manter o projeto em MIT.

### Outros projetos vistos na pesquisa (novembro de 2026)
Apps Kotlin/Compose de exemplo com ELM327, úteis para comparar abordagens, sem código aproveitado:
ETSoftwareStudio/obd-scanner-android, gustavozacarias04/obd2-dashboard-android, mtgofa/obd2-app,
nonoskygt/carsoc (painel para central multimídia), YagoBorba/BorbaCarLabs (BLE, Bluetooth e Wi-Fi).

## Artigos

### Engenharia reversa do MUT-III SE (Mohammad-Ali Bandzar)
- Parte 1: https://mohammad-ali.bandzar.com/blog/reverse-engineering-mut-iii-se-decrypting-exdf-files/
- Parte 2: https://mohammad-ali.bandzar.com/blog/reverse-engineering-mut-iii-se-exploring-commondb/
- Por que importa: o MUT-III é a ferramenta oficial da Mitsubishi. Esses artigos mostram como os
  arquivos de definição dela foram lidos, que é de onde vieram os parâmetros do serviço 21 usados no
  OBDLogger. É o caminho mais promissor para achar os dados da 4M41 sem tentativa e erro.

### Manual de serviço
- Manuais de fábrica da Mitsubishi trazem a lista de dados de diagnóstico e as faixas normais. Vale
  procurar o da Pajero (Montero) NW/NX com motor 4M41 para validar valores lidos.

## Normas e especificações

- **SAE J1979 / ISO 15031-5**: serviços OBD-II (01 dados atuais, 03/07/0A códigos, 04 limpeza,
  09 identificação) e as fórmulas dos PIDs padrão. Resumo público: https://en.wikipedia.org/wiki/OBD-II_PIDs
- **ISO 15765-4**: OBD sobre CAN (endereços 7DF, 7E0–7E7 e respostas 7E8–7EF).
- **ISO 14229 (UDS)** e **ISO 14230 (KWP2000)**: origem dos serviços 21/22 e dos códigos de resposta
  negativa (7F serviço código).
- **ELM327 (ELM Electronics)**: folha de dados com os comandos AT usados (`ATZ`, `ATE0`, `ATS0`, `ATH0`,
  `ATCAF1`, `ATAT1`, `ATSP`, `ATSH`, `ATCRA`, `ATAR`, `ATRV`, `ATDPN`). Adaptadores clones divergem da
  folha de dados; o Vgate iCar Pro 2S é de boa qualidade.

## Android

- Bluetooth Low Energy (GATT, MTU, notificações por CCCD): https://developer.android.com/develop/connectivity/bluetooth/ble/ble-overview
- Permissões de Bluetooth no Android 12+ (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`): https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
- Jetpack Compose e Material 3: https://developer.android.com/jetpack/compose

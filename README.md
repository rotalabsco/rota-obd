# Rota.OBD

App Android de código aberto da [RotaLabs](https://rotalabs.co) para ler o seu carro por um adaptador OBD-II ELM327: dados em tempo real,
códigos de falha e, principalmente, os **dados do fabricante** que os apps comerciais escondem atrás
de assinatura.

Feito primeiro para centrais multimídia Android e para a **Mitsubishi Pajero Full 3.2 DI-D (4M41)**,
mas o núcleo é genérico: qualquer carro OBD-II em CAN funciona com os dados padrão, e cada modelo novo
entra como um **perfil** descoberto pela comunidade.

> Estado: **versão 0.1, fase de descoberta.** Lê tudo o que o carro expõe e grava em arquivo para
> montarmos os perfis. Consumo real e painel flutuante vêm nas próximas etapas.

## O que já faz

- Conecta por **BLE** (Vgate iCar Pro, clones FFF0/FFE0, Microchip) ou **Bluetooth clássico** (SPP)
- **Descoberta**: módulos presentes, PIDs padrão suportados, VIN e calibração, códigos de falha
  (armazenados, pendentes e permanentes) e varredura do **serviço 21** (dados Mitsubishi) no motor e no câmbio
- **Ao vivo**: os PIDs padrão que o motor suporta, em blocos grandes
- **Falhas**: leitura por módulo e limpeza (modo 04) com confirmação
- **Explorar 21**: lê um identificador do fabricante sem parar e destaca os bytes que mudam
- Grava cada leitura em **NDJSON** e exporta tudo em .zip; relatório em texto para compartilhar
- **Modo demonstração** com um ELM327 simulado, para testar sem carro
- **Garagem**: carro (chassi e calibração lidos sozinhos), abastecimentos com consumo tanque a tanque,
  manutenções com aviso por km ou data, e pergunta automática quando o tanque sobe
- **Vencimentos**: IPVA, licenciamento, seguro, CNH e outros, com aviso antes do prazo
- **Modo de espera**: pausa as leituras com o motor desligado, solta o adaptador para ele dormir e
  reconecta sozinho quando o carro liga

## Instalar

1. Baixe o `rota-obd-debug.apk` em **Releases → Versão de teste** (gerado automaticamente a cada mudança).
2. Na central, permita instalar de fontes desconhecidas e abra o APK.
3. Feche o Car Scanner/Torque: o adaptador aceita uma conexão por vez.
4. Ignição ligada → **Conexão → Procurar adaptadores** → toque no seu adaptador.
5. **Descoberta → Iniciar descoberta**. Ao final, **Exportar sessões (.zip)** e **Compartilhar relatório**.

## Roteiro

1. **Descoberta** (v0.1): mapear o que a Pajero 4M41 expõe no motor e no câmbio.
2. **Garagem** (feito): perfil do carro (preenchido pelo VIN), abastecimentos, manutenções com lembrete por km
   ou data, e consumo real tanque a tanque. Detecta o abastecimento pela subida do nível de combustível
   e pergunta litros e valor.
3. **Viagens e consumo**: gravação automática por viagem e consumo instantâneo calculado pelo volume
   injetado, calibrado pelos abastecimentos.
4. **Sincronização**: ao chegar no Wi-Fi de casa, envia resumo para o **Home Assistant** por MQTT
   (sensores criados sozinhos) e as sessões brutas para uma pasta sincronizada.
5. **Painel flutuante** por cima do Maps e do player.

## Como ajudar com o seu carro

Rode a descoberta e abra uma *issue* com o modelo, ano, motor e câmbio, anexando o relatório e o .zip.
Com isso dá para começar um perfil em `profiles/`. Para achar o significado de cada byte, use
**Explorar 21** enquanto acelera, liga o ar ou o motor esquenta, e anote quais bytes acompanham.

## Segurança

O app só **lê**. A única escrita é apagar códigos de falha (modo 04), com confirmação. Serviços que
alteram a central (2E, 2F, 31, 27) não são enviados. Não use a tela enquanto dirige.

## Desenvolvimento

Kotlin + Jetpack Compose, sem dependências além do AndroidX.

```
app/src/main/java/co/rotalabs/obd/
  core/      protocolo ELM327, parser ISO-TP, PIDs, DTCs, descoberta, gravação (sem Android, testável)
  android/   transporte BLE e Bluetooth clássico, busca de dispositivos
  ui/        telas Compose e ViewModel
profiles/    perfis por veículo (dados do fabricante)
docs/        documentação: referências, arquitetura, protocolo, dados, decisões, build
```

Abra a pasta no Android Studio (Gradle 8.11+, JDK 17) ou rode `gradle testDebugUnitTest assembleDebug`.
Os testes do `core` rodam contra o simulador, sem aparelho.

## Documentação

A pasta [`docs/`](docs/README.md) reúne as referências usadas, a arquitetura, o modo de espera, o
formato dos dados, a ficha da Pajero 4M41, as decisões de projeto e como compilar.

## Créditos

- [OBDLogger (NaleenB/obd-profile-logger)](https://github.com/NaleenB/obd-profile-logger), MIT: as notas de
  protocolo sobre ELM327 BLE e o serviço 21 da Mitsubishi guiaram o desenho deste projeto. Nenhum código copiado.
- [kotlin-obd-api](https://github.com/eltonvs/kotlin-obd-api) e [AndrOBD](https://github.com/fr3ts0n/AndrOBD)
  como referência de PIDs e de arquitetura.

## Licença

MIT. Sem vínculo com a Mitsubishi, ELM Electronics, Vgate ou fabricantes de apps de diagnóstico.

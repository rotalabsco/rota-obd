# Arquitetura

Kotlin + Jetpack Compose, um único módulo (`app`), sem dependências além de AndroidX e coroutines.

```
app/src/main/java/io/github/obdlivre/
  core/       Kotlin puro (sem Android): protocolo, cálculos e arquivos. Testável no JVM.
    ElmLink.kt        ByteLink (canal de bytes) e ElmExchanger (pergunta → resposta até o '>')
    ElmParser.kt      interpreta respostas: quadro único, multiquadro ISO-TP, negativas, erros
    Elm327.kt         cliente ELM327: inicialização, endereçamento por módulo, PIDs, DTCs, modo 09
    ObdPids.kt        PIDs padrão do modo 01 com fórmulas e o mapa de suportados
    Dtc.kt            decodificação de códigos de falha (P/C/B/U)
    Discovery.kt      descoberta completa e relatório em texto
    Recorder.kt       gravação NDJSON e JSON mínimo (escrita)
    MiniJson.kt       JSON mínimo (leitura)
    SimulatedLink.kt  ELM327 simulado (modo demonstração e testes)
    SleepPolicy.kt    regra do modo de espera (ativo / silencioso / liberar)
    Garage.kt         carro, abastecimentos, manutenções, consumo tanque a tanque
    Deadlines.kt      vencimentos (IPVA, licenciamento, seguro, CNH)
  android/    transporte e busca de dispositivos
    BleLink.kt        BLE com detecção automática das características (Vgate, FFF0, FFE0, Microchip)
    ClassicLink.kt    Bluetooth clássico (SPP)
    DeviceScanner.kt  pareados + busca BLE
  ui/         telas Compose e estado
    ObdViewModel.kt   conexão, operações exclusivas, modo de espera, garagem, vencimentos
    MainActivity.kt   navegação lateral e telas de conexão, descoberta, ao vivo, falhas, explorar, registro
    GarageScreen.kt   carro, abastecimentos, manutenção
    DeadlinesSection.kt vencimentos
app/src/test/   testes do core contra o simulador (JUnit 4)
```

## Camadas

1. **ByteLink** só move bytes. Há três implementações: BLE, clássico e simulador. Um link Wi-Fi
   (socket TCP na porta 35000) entraria aqui sem mexer no resto.
2. **ElmExchanger** serializa as perguntas com um mutex e junta os pedaços até o prompt `>`.
3. **Elm327** sabe o protocolo: comandos AT, `ATSH`/`ATCRA` por módulo, leitura de PIDs, DTCs, VIN.
   A única escrita no carro é `clearDtcs` (modo 04).
4. **Discovery**, **Garage**, **Deadlines**, **SleepPolicy** são regras puras, testadas no JVM.
5. **ObdViewModel** orquestra tudo e expõe `StateFlow` para as telas.

## Fluxos principais

### Conexão
`connect()` → abre o link → `Elm327.initialize()` (ATZ, ATE0, ATL0, ATS0, ATH0, ATCAF1, ATAT1, ATSP6,
teste 0100, cai para ATSP0 se falhar) → grava sessão → inicia o vigia do modo de espera → identifica o
carro (VIN e calibração pelo modo 09) e lê o nível do tanque (PID 2F).

### Operações exclusivas
Só uma operação usa o adaptador por vez (`exclusive`). Uma nova cancela a anterior. Ações do usuário
contam como atividade para o modo de espera; a identificação automática não.

### Descoberta
Módulos presentes (0100 em 7E0–7E7) → PIDs suportados e uma leitura de cada → modo 09 → DTCs 03/07/0A →
varredura do serviço 21 de 00 a FF (aborta cedo se as 16 primeiras respostas forem "serviço não
suportado" ou silêncio) → relatório em texto e arquivo.

### Detecção de abastecimento
Ao conectar e durante a leitura ao vivo, o nível do tanque é comparado com o último salvo. Subida de 15
pontos ou mais abre o formulário de abastecimento com litros estimados (se o tanque estiver cadastrado).

## Testes

`app/src/test/java/io/github/obdlivre/core/`:
- `CoreTest`: parser, ISO-TP, negativas, DTCs, mapa de PIDs, descoberta contra o simulador, JSON.
- `GarageTest`: consumo tanque a tanque, estado de manutenção, JSON, arquivo corrompido, números em pt-BR.
- `SleepAndDeadlinesTest`: tensão do ATRV, regras do modo de espera, vencimentos.

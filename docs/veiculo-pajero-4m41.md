# Veículo de referência: Pajero Full 3.2 DI-D (4M41)

## O que já se sabe

| Item | Valor | Fonte |
|---|---|---|
| Modelo | Mitsubishi Pajero Full 5 portas, diesel, 2013/14 | dono |
| Motor | 3.2 DI-D, 4M41 | dono |
| Câmbio | automático | dono |
| Protocolo OBD do motor | ISO 15765-4 CAN 11 bits, 500 kbps | Car Scanner na central |
| Prefixo do chassi | `JMYLYV` (Mitsubishi, Japão), ano-modelo 2014 pelo 10º caractere | Car Scanner |
| Calibração da ECU do motor | `1860C02202` | Car Scanner |
| Adaptador | Vgate iCar Pro 2S (FCC ID 2A45F-CV306), BLE, com modo de espera | foto |
| Central multimídia | ADAK LINK8S, Android 14, 6 GB RAM, 128 GB | tela "Sobre" |

O chassi completo não fica no repositório público, só o prefixo.

## O que falta descobrir (pela Descoberta, com motor ligado)

1. Quais PIDs padrão o motor suporta. Em especial: `2F` nível do tanque (decide se a detecção de
   abastecimento funciona), `5E` vazão de combustível (consumo direto), `5C` temperatura do óleo.
2. Se o câmbio responde em `7E1`, e o quê.
3. Se o motor e o câmbio respondem ao **serviço 21** e quais identificadores (LID) existem.
4. Nos LIDs que responderem, com **Explorar 21**, achar:
   - volume injetado por ciclo (para o consumo instantâneo);
   - temperatura do fluido do câmbio (para o alerta de superaquecimento);
   - pressão real e alvo do rail, pressão do turbo, temperatura do combustível.

## Primeiro teste no carro (21/11/2026)

Motor ligado, parado, em marcha lenta (~650 rpm), com o adaptador anunciado como `IOS-Vlink` (ELM327 v2.3, BLE).

| Pergunta | Resposta |
|---|---|
| PIDs padrão do motor | 26: 01 04 05 0B 0C 0D 0F 10 11 1C 1F 21 23 2C 2D 30 31 33 3C 42 45 49 4A 4C 4D 4E |
| `2F` nível do tanque | **não suportado**: a detecção de abastecimento pelo nível não funciona neste carro |
| `5E` vazão de combustível | **não suportado**: o consumo tem de vir do serviço 21 |
| `5C` temperatura do óleo | não suportado |
| Turbo | `0B` (MAP) e `33` (barométrica) existem: pressão do turbo = MAP − barométrica |
| Rail | `23` responde (≈30 MPa em marcha lenta) |
| Câmbio em `7E1` | responde: `TCM-TransmisCtrl`, calibração `8631A87001`, 12 PIDs padrão |
| Serviço 21 | motor: 101 LIDs de 256; câmbio: 14 LIDs (`2101`–`210B`, `21E0`, `21E1`, `21E4`) |
| Códigos | P2426 (EGR, válvula de arrefecimento, sinal baixo) armazenado e pendente no motor, com 38 km rodados de luz acesa; apagado no teste |

Explorar 21 foi usado só no `2101` do motor, por ~60 s parado: 61 respostas diferentes, sem acelerar nem
ligar o ar. As 22 leituras sem resposta ficaram todas no fim (ignição desligada). Ainda não dá para
atribuir significado a nenhum byte.

## Viagem de 21/11/2026 (~151 km, Ao vivo, versão 0.1.14)

Só PIDs padrão: a gravação da viagem com o serviço 21 ainda não existia nesta versão.

| O que | Resultado |
|---|---|
| Manual, reduzindo até a 1ª (~15:50) | pedal e carga em 0, rotação de 892 para 2.122 rpm com a velocidade caindo de 74 para 26 km/h: freio motor com injeção cortada |
| Serra no manual (16:31–16:33) | carga 0 por ~1,5 min a 2.500–3.200 rpm (relação rpm/km/h ≈ 50: 2ª); temperatura do motor caiu de 89 para 85 °C |
| D com o pé solto | a rotação volta à marcha lenta (~700 rpm) mesmo a 70 km/h: o conversor desacopla, sem freio motor |
| Temperatura do motor | máx. 92 °C |
| Turbo (MAP − barométrica) | máx. 1,33 bar |
| Rail | máx. 1.380 bar |
| P2426 | voltou depois de ~150 km (não estava às 14:17, estava às 21:35). Recorrente com o diesel daqui; o dono usa aditivo |
| Consumo | Car Scanner marcou ~9,5 km/L na viagem. O modelo carga × rotação deu 14,5 km/L com 80 mm³ de referência; calibrado para 122 mm³ (veja `core/FuelEstimate.kt`) |

O Car Scanner mostrava o volume injetado em litros: vem de um identificador do serviço 21 (perfil Mitsubishi). Achar esse
byte é o próximo passo, com a gravação da viagem, que agora lê em rodízio todos os LIDs que responderam.

## Plano de teste no carro

1. Fechar o Car Scanner. Ignição ligada, abrir o app, conectar ao Vgate.
2. Conferir em Garagem → Carro se chassi e calibração apareceram.
3. Ligar o motor e rodar a Descoberta (1 a 3 minutos).
4. Exportar o `.zip` e compartilhar o relatório.
5. Ver na aba Registro se apareceu "A ECU não informa o nível do tanque (PID 2F)".
6. Com o motor aquecendo, abrir Explorar 21 em cada LID que respondeu e anotar os bytes que mudam com
   aceleração, com o ar-condicionado e com a temperatura.

## Consumo em diesel

A conta pelo MAF não vale para diesel. Ordem de preferência:
1. PID `5E`, se existir.
2. Volume injetado (mm³ por curso) do serviço 21:
   `L/h = mm³_por_curso × 4 cilindros × (rpm / 2) × 60 / 1.000.000`
3. Correção do fator pelos abastecimentos de tanque cheio registrados na Garagem.

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

## Coleta de 22/11/2026 (gravação da viagem, 11 min, 6,9 km, versão 0.1.20)

Os 115 LIDs que respondem foram lidos ~40 vezes cada; os fixos, 546. O motor foi de 64 a 88 °C. Cruzando cada
byte com rotação, velocidade, carga e temperatura do motor (correlação), e com a troca P-R-N-D cronometrada:

| Módulo | LID | Byte de dados | Significado | Confiança |
|---|---|---|---|---|
| Câmbio | `2108` | 4 | **temperatura do fluido**, °C = valor − 40. A frio bateu com a ambiente (29 °C); na coleta: 38 °C (07:42), 46 °C (09:13), 47 → 58 °C na viagem, 59 °C na chegada | alta |
| Câmbio | `2102` | 6 | a mesma temperatura do fluido, com − 50 | alta |
| Câmbio | `2108` | 2 | **alavanca**: E0 = P, 00 = R, 20 = N, 40 = D (bits altos); 0x10 no byte 0 durante a troca | alta |
| Câmbio | `2102` | 9 | **marcha engatada**, um bit por marcha: 04 = 1ª, 08 = 2ª, 10 = 3ª, 20 = 4ª, 40 = 5ª (rpm/km/h ≈ 63, 45, 31, 22) | alta |
| Câmbio | `2105` | 5 | temperatura do **motor** que o câmbio recebe (− 40); **não** é o fluido | alta |
| Câmbio | `2108` | 6 | 0x15 em P/N, 0x00 com marcha engatada (R/D) | média |
| Motor | `2102` | 1 | temperatura do motor (− 40), igual ao PID 05 | alta |
| Motor | `2101` | 1 | velocidade em km/h | alta |
| Motor | `2144` | 0–1 | velocidade (× ~128) | alta |
| Motor | `2158` | 4 | acompanha a carga (r = 0,98) e vai a 0 no corte de injeção: candidato a volume injetado (≈ 2 × carga) | média |
| Motor | `2151` | 0 | acompanha a carga (r = 0,99): outro candidato | média |
| Motor | `2115` | 1–2 | contador que subiu 7 em 6,9 km; entre a manhã de 21/11 e 22/11 subiu ~179 km: provável hodômetro em km | média (falta comparar com o painel) |
| Motor | `211A` 1–2, `2103` 2–3 | | outros contadores que sobem 1 por km | média |

O que foi para o app (perfil `core/PerfilMitsubishi.kt`, só para a calibração 1860C02202): temperatura do fluido do
câmbio (alerta a 100 °C, sério a 115 °C) e marcha engatada, no Ao vivo, no painel e na gravação da viagem.

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

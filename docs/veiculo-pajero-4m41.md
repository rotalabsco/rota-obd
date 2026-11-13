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

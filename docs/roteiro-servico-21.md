# Roteiro do segundo teste no carro: serviço 21 e tela da central

Objetivo: descobrir o significado dos bytes do serviço 21 que destravam o consumo real, a temperatura do
câmbio e o alerta de superaquecimento. Na mesma ida, medir a densidade da tela da central.

**No app, use a aba Coleta.** Ela traz este roteiro passo a passo: diz o que fazer em português, mostra
cada troca com contagem regressiva, roda a leitura sozinha e marca no arquivo o começo e o fim de cada
passo, sem precisar anotar horário. Este documento explica o porquê de cada passo.

O significado de cada byte vem de duas fontes: a comparação entre relatórios da Descoberta tirados em
situações diferentes, e o momento das ações durante as leituras repetidas.

## O que já se sabe (do primeiro teste, 21/11/2026)

Temperaturas OBD vêm com +40: 30 °C aparece como `46` e 28 °C como `44`. No relatório do primeiro teste,
o líquido de arrefecimento estava a 30 °C e o ar de admissão a 28 °C.

| Módulo | LID | Bytes no teste | Hipótese |
|---|---|---|---|
| Motor | `2102` | `2D 46 46 44 CA` | bytes 1 e 2 = temperaturas a 30 °C (arrefecimento, combustível?); byte 3 = ar de admissão a 28 °C |
| Motor | `214F` | `46 46 40 4B` | outro par de temperaturas a 30 °C |
| Motor | `2101` | `B5 00 1A 68` | byte 4 ficou entre 24 e 27 em marcha lenta e foi a **0** perto do fim: pode ser o volume injetado (motor desligado = 0). Byte 2 oscila entre 170 e 186 |
| Câmbio | `2105` | `40 42 0F D0 02 47 00 00` | byte 5 = `47`, 31 °C, igual ao arrefecimento que o câmbio informou: candidato a temperatura do fluido |
| Câmbio | `2108` | `00 00 E0 00 45 00 14 40` | byte 4 = `45`, 29 °C: outro candidato a temperatura |

Com o motor frio, o fluido do câmbio, o líquido de arrefecimento e o ar estão todos perto da temperatura
ambiente e não dá para separar um do outro. No aquecimento eles se separam: o líquido de arrefecimento
sobe primeiro, e o fluido do câmbio só sobe com o carro rodando. Por isso o teste **começa com o motor
frio**.

Fora do roteiro: LIDs que vieram todos zerados, a faixa `21A0`–`21AC` (parece uma tabela fixa, com
`7FFF` de preenchimento), os de identificação `21E0`, `21E1` e `21E4`, e o `215D`, que repete o `2113`.

## Antes de sair

1. **Exporte o .zip na central** (Descoberta → Exportar sessões (.zip)). Desinstalar o app apaga a garagem, os
   vencimentos e as sessões, e o app ainda não importa nada de volta.
2. Desinstale o Rota.OBD e instale a **Versão de teste #8** pelo navegador:
   github.com/rotalabsco/rota-obd/releases/latest. Daí em diante as atualizações chegam pelo app.
3. Refaça o cadastro do carro na Garagem, se preciso.
4. Combine com alguém para ir junto: as etapas com o carro rodando precisam de um **passageiro mexendo
   no app**. Quem dirige não toca na central.
5. Tenha à mão um jeito de anotar a hora das ações. Um cronômetro no celular, com a hora de início de
   cada Explorar 21, já resolve.

## Etapa 1: tela da central (5 min, carro desligado)

1. Configurações do Android → Sobre → toque 7 vezes em "Número da versão" para liberar as Opções do
   desenvolvedor.
2. Opções do desenvolvedor → **Largura mínima**: anote o número (em dp). 720 = 160 dpi; 480 = 240 dpi.
3. Tire prints do Ao vivo, Falhas, Garagem e Conexão (no Android da central costuma ser energia +
   volume baixo, ou pela barra de notificações). Essas imagens vão para `docs/prints/`.

## Etapa 2: Descoberta em cinco situações (motor frio, carro parado)

Rode a **Descoberta** inteira em cada situação, sem mexer em nada durante a leitura. Cada uma gera um
relatório com todos os LIDs e os PIDs padrão daquele momento.

| # | Situação | Para separar |
|---|---|---|
| D1 | Ignição ligada, motor **desligado** | o que é zero com o motor parado (injeção, rotação, vazão) |
| D2 | Motor **frio**, logo após a partida, marcha lenta | referência fria das temperaturas |
| D3 | Marcha lenta com o **ar-condicionado ligado** | compressor, aumento da marcha lenta, carga |
| D4 | Rotação **mantida em ~2.000 rpm** (pé firme no acelerador) | injeção, rail, turbo, posição do pedal |
| D5 | De volta à marcha lenta, ar desligado | confirma o que volta ao valor de D2 |

## Etapa 3: Explorar 21 com a mesma sequência (motor ainda frio, parado)

Abra o **Explorar 21** em cada LID da lista. Siga sempre a mesma sequência de 60 s, contando a partir do
toque em Explorar:

| Tempo | Ação |
|---|---|
| 0–15 s | marcha lenta, sem tocar em nada |
| 15–25 s | acelera até ~2.000 rpm e segura |
| 25–35 s | solta o acelerador |
| 35–45 s | liga o ar-condicionado |
| 45–60 s | desliga o ar |

Motor, nesta ordem: `2101`, `2102`, `214F`, `2104`, `214C`, `2103`, `2109`, `2115`, `2116`, `2147`,
`214A`, `214D`, `214E`, `2140`, `2141`, `2142`, `21B0`, `21BC`.

Câmbio, com o **pé no freio** e o carro parado, troca de alavanca no lugar do acelerador. A sequência
é: 0–10 s em P, 10–20 s em R, 20–30 s em N, 30–45 s em D, 45–60 s de volta em P. LIDs `2101` a `210B`
(são 11).

## Etapa 4: rodando, até o motor esquentar (passageiro no app)

Rode 15 a 20 minutos em trajeto normal, com trocas de marcha. O passageiro deixa o Explorar 21 aberto:

1. Câmbio `2105` durante uns 5 minutos (fluido esquentando, trocas de marcha).
2. Câmbio `2108` durante uns 5 minutos.
3. Motor `2101` numa reta, com acelerações e alívios (injeção).

## Etapa 5: motor quente, parado

Com o ponteiro de temperatura no meio:

| # | Situação |
|---|---|
| D6 | Marcha lenta, ar desligado |
| D7 | Marcha lenta, ar ligado |

Depois repita o Explorar 21 nos LIDs `2102`, `214F` e câmbio `2105` e `2108` (30 s parado basta).
Com tudo quente, as temperaturas que eram iguais no frio aparecem separadas.

## Depois do teste

Exporte o .zip (Descoberta → Exportar sessões) e compartilhe. Ele deve conter:

- os 7 relatórios da Descoberta (D1 a D7);
- as sessões com os Explorar 21;
- o horário de início de cada Explorar 21 e qualquer coisa fora do roteiro (buraco, ventoinha
  ligando, luz de falha).

A análise compara os relatórios entre si e alinha os bytes do Explorar 21 com a sequência de ações. O
que for confirmado entra em `veiculo-pajero-4m41.md` e vira leitura no Ao vivo.

O chassi completo e o número de série do câmbio (que aparece em `21E1`) **não** entram no repositório.
Os dados brutos ficam em `dados-locais/`, que está fora do git.
